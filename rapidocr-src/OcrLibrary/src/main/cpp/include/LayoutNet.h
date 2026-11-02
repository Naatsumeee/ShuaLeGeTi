#ifndef __LAYOUT_NET_H__
#define __LAYOUT_NET_H__

#include "OcrStruct.h"
#include "onnxruntime/core/session/onnxruntime_cxx_api.h"
#include <opencv2/core.hpp>
#include <opencv2/imgproc.hpp>
#include <android/asset_manager.h>

/**
 * PP-DocLayout-S 版面分析模型（480x480 固定输入）。
 *
 * 与 DbNet/RecNet 的区别：它不做像素级文本检测，而是给出「段落级区域」——
 * 这里是正文、那里是标题、那块是表格、这块是公式、页眉页脚在哪。
 * 用途是给 OCR 结果算「栏边界」并排除页眉页脚，而不是按框裁剪重识别
 * （框是段落级的，裁开就丢上下文，反而更差）。
 *
 * 输入：
 *   image        : [1, 3, 480, 480] float32，ImageNet 归一化
 *   scale_factor : [1, 2] float32 = [sy, sx] —— 注意是「高在前、宽在后」，
 *                  与常见的 [w, h] 相反；实测传反了框会飞出原图。
 * 输出：
 *   fetch_name_0 : [N, 6] = [labelId, score, x1, y1, x2, y2]，坐标已是原图尺度
 *   fetch_name_1 : [N]    检测框数量
 */
struct LayoutBox {
    int labelId;
    float score;
    cv::Rect2f rect;
};

class LayoutNet {
public:
    LayoutNet();

    ~LayoutNet();

    void setNumThread(int numOfThread);

    /** 返回是否加载成功：模型缺失时只降级为「不做版面分析」，不能崩。 */
    bool initModel(AAssetManager *mgr, const std::string &name);

    bool isReady() const { return session != nullptr; }

    std::vector<LayoutBox> detect(cv::Mat &src, float scoreThresh);

private:
    Ort::Session *session = nullptr;
    Ort::Env ortEnv = Ort::Env(ORT_LOGGING_LEVEL_ERROR, "LayoutNet");
    Ort::SessionOptions sessionOptions = Ort::SessionOptions();

    std::vector<Ort::AllocatedStringPtr> inputNamesPtr;
    std::vector<Ort::AllocatedStringPtr> outputNamesPtr;
};

/** labelId -> 标签名（与模型输出严格对齐，顺序即类别下标）。 */
const char *layoutLabelName(int labelId);

#endif //__LAYOUT_NET_H__
