#include "LayoutNet.h"
#include "OcrUtils.h"
#include <algorithm>

namespace {
const int INPUT_SIZE = 480;
const float MEAN[3] = {0.485f, 0.456f, 0.406f};
const float STD[3] = {0.229f, 0.224f, 0.225f};

const char *LABELS[] = {
        "paragraph_title", "image", "text", "number", "abstract", "content",
        "figure_title", "formula", "table", "table_title", "reference", "doc_title",
        "footnote", "header", "algorithm", "footer", "seal", "chart_title",
        "chart", "formula_number", "header_image", "footer_image", "aside_text"
};
const int LABEL_COUNT = (int) (sizeof(LABELS) / sizeof(LABELS[0]));

float iou(const cv::Rect2f &a, const cv::Rect2f &b) {
    float x1 = (std::max)(a.x, b.x);
    float y1 = (std::max)(a.y, b.y);
    float x2 = (std::min)(a.x + a.width, b.x + b.width);
    float y2 = (std::min)(a.y + a.height, b.y + b.height);
    float iw = (std::max)(0.0f, x2 - x1);
    float ih = (std::max)(0.0f, y2 - y1);
    float inter = iw * ih;
    float uni = a.width * a.height + b.width * b.height - inter;
    return uni > 0 ? inter / uni : 0.0f;
}
}

const char *layoutLabelName(int labelId) {
    if (labelId < 0 || labelId >= LABEL_COUNT) return "text";
    return LABELS[labelId];
}

LayoutNet::LayoutNet() {}

LayoutNet::~LayoutNet() {
    delete session;
    inputNamesPtr.clear();
    outputNamesPtr.clear();
}

void LayoutNet::setNumThread(int numOfThread) {
    sessionOptions.SetIntraOpNumThreads(numOfThread);
    sessionOptions.SetInterOpNumThreads(numOfThread);
    sessionOptions.SetGraphOptimizationLevel(GraphOptimizationLevel::ORT_ENABLE_EXTENDED);
}

bool LayoutNet::initModel(AAssetManager *mgr, const std::string &name) {
    int length = 0;
    void *data = getModelDataFromAssets(mgr, name.c_str(), length);
    if (data == nullptr || length <= 0) {
        LOGE("LayoutNet model [%s] not found in assets, layout analysis disabled", name.c_str());
        if (data != nullptr) free(data);
        return false;
    }
    session = new Ort::Session(ortEnv, data, length, sessionOptions);
    free(data);
    inputNamesPtr = getInputNames(session);
    outputNamesPtr = getOutputNames(session);
    LOGI("LayoutNet ready, inputs=%zu outputs=%zu", inputNamesPtr.size(), outputNamesPtr.size());
    return true;
}

std::vector<LayoutBox> LayoutNet::detect(cv::Mat &src, float scoreThresh) {
    std::vector<LayoutBox> boxes;
    if (session == nullptr) return boxes;

    const int srcW = src.cols;
    const int srcH = src.rows;

    // letterbox：等比缩放后左上角贴边补白。
    // 之所以不直接 resize 成 480x480：实测直接拉伸会让模型退化成「整页一个 content 大框」。
    float s = (std::min)((float) INPUT_SIZE / (float) srcW, (float) INPUT_SIZE / (float) srcH);
    int newW = (std::max)(1, (int) (srcW * s));
    int newH = (std::max)(1, (int) (srcH * s));

    cv::Mat resized;
    cv::resize(src, resized, cv::Size(newW, newH), 0, 0, cv::INTER_LINEAR);

    cv::Mat canvas(INPUT_SIZE, INPUT_SIZE, src.type(), cv::Scalar(255, 255, 255));
    resized.copyTo(canvas(cv::Rect(0, 0, newW, newH)));

    // HWC uint8 BGR -> CHW float32 RGB，ImageNet 归一化
    cv::Mat rgb;
    cv::cvtColor(canvas, rgb, cv::COLOR_BGR2RGB);
    rgb.convertTo(rgb, CV_32FC3, 1.0 / 255.0);

    std::vector<float> inputValues(3 * INPUT_SIZE * INPUT_SIZE);
    const int plane = INPUT_SIZE * INPUT_SIZE;
    for (int y = 0; y < INPUT_SIZE; ++y) {
        const cv::Vec3f *row = rgb.ptr<cv::Vec3f>(y);
        for (int x = 0; x < INPUT_SIZE; ++x) {
            for (int c = 0; c < 3; ++c) {
                inputValues[c * plane + y * INPUT_SIZE + x] = (row[x][c] - MEAN[c]) / STD[c];
            }
        }
    }

    auto memoryInfo = Ort::MemoryInfo::CreateCpu(OrtDeviceAllocator, OrtMemTypeCPU);
    std::array<int64_t, 4> imageShape{1, 3, INPUT_SIZE, INPUT_SIZE};
    // scale_factor 是 [sy, sx]：高在前。实测反了框会超出原图宽度。
    std::array<int64_t, 2> scaleShape{1, 2};
    float scaleValues[2] = {s, s};

    std::vector<Ort::Value> inputs;
    inputs.push_back(Ort::Value::CreateTensor<float>(memoryInfo, inputValues.data(),
                                                     inputValues.size(), imageShape.data(),
                                                     imageShape.size()));
    inputs.push_back(Ort::Value::CreateTensor<float>(memoryInfo, scaleValues, 2,
                                                     scaleShape.data(), scaleShape.size()));

    std::vector<const char *> inputNames;
    for (auto &n : inputNamesPtr) inputNames.push_back(n.get());
    std::vector<const char *> outputNames;
    for (auto &n : outputNamesPtr) outputNames.push_back(n.get());

    auto outputs = session->Run(Ort::RunOptions{nullptr}, inputNames.data(), inputs.data(),
                                inputs.size(), outputNames.data(), outputNames.size());
    if (outputs.size() < 2) return boxes;

    std::vector<int64_t> detShape = outputs[0].GetTensorTypeAndShapeInfo().GetShape();
    if (detShape.size() != 2 || detShape[1] < 6) return boxes;
    const float *det = outputs[0].GetTensorMutableData<float>();
    const int rows = (int) detShape[0];

    std::vector<int64_t> numShape = outputs[1].GetTensorTypeAndShapeInfo().GetShape();
    int count = rows;
    if (numShape.size() == 1) {
        const float *numPtr = outputs[1].GetTensorMutableData<float>();
        int n = (int) numPtr[0];
        if (n > 0 && n < rows) count = n;
    }

    for (int i = 0; i < count; ++i) {
        const float *r = det + i * detShape[1];
        float score = r[1];
        if (score < scoreThresh) continue;
        float x1 = (std::max)(0.0f, r[2]);
        float y1 = (std::max)(0.0f, r[3]);
        float x2 = (std::min)((float) srcW, r[4]);
        float y2 = (std::min)((float) srcH, r[5]);
        if (x2 - x1 < 2 || y2 - y1 < 2) continue;
        LayoutBox box;
        box.labelId = (int) r[0];
        box.score = score;
        box.rect = cv::Rect2f(x1, y1, x2 - x1, y2 - y1);
        boxes.push_back(box);
    }

    // NMS：模型 raw 输出恒为 100 行（自带上限截断），同一条正文常被吐两三次。
    std::sort(boxes.begin(), boxes.end(), [](const LayoutBox &a, const LayoutBox &b) {
        return a.score > b.score;
    });
    std::vector<LayoutBox> kept;
    for (const auto &b : boxes) {
        bool dup = false;
        for (const auto &k : kept) {
            if (k.labelId == b.labelId && iou(k.rect, b.rect) > 0.5f) {
                dup = true;
                break;
            }
        }
        if (!dup) kept.push_back(b);
    }
    return kept;
}
