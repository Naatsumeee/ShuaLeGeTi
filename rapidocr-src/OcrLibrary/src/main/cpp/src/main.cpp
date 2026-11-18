#include "OcrResultUtils.h"
#include "BitmapUtils.h"
#include "OcrLite.h"
#include "OcrUtils.h"
#include "LayoutNet.h"

extern "C" JNIEXPORT jlong JNICALL
Java_com_benjaminwan_ocrlibrary_OcrEngine_nativeCreate(JNIEnv *env, jobject thiz,
                                                       jobject assetManager, jint numThread,
                                                       jstring detName, jstring clsName,
                                                       jstring recName, jstring keysName) {
    std::string modelDetName = jstringTostring(env, detName);
    std::string modelClsName = jstringTostring(env, clsName);
    std::string modelRecName = jstringTostring(env, recName);
    std::string modelKeysName = jstringTostring(env, keysName);
    auto *engine = new OcrLite();
    engine->init(env, assetManager, numThread, modelDetName, modelClsName, modelRecName,
                 modelKeysName);
    return reinterpret_cast<jlong>(engine);
}

extern "C" JNIEXPORT void JNICALL
Java_com_benjaminwan_ocrlibrary_OcrEngine_nativeRelease(JNIEnv *env, jobject thiz, jlong handle) {
    delete reinterpret_cast<OcrLite *>(handle);
}

/**
 * 版面分析模型单独初始化：它是可选能力，模型缺失时返回 false，
 * 调用方降级到「不做版面分析」，不能因为一个可选模型把 OCR 整个拖垮。
 */
extern "C" JNIEXPORT jlong JNICALL
Java_com_benjaminwan_ocrlibrary_LayoutEngine_nativeCreate(JNIEnv *env, jobject thiz,
                                                          jobject assetManager, jint numThread,
                                                          jstring modelName) {
    std::string name = jstringTostring(env, modelName);
    AAssetManager *mgr = AAssetManager_fromJava(env, assetManager);
    if (mgr == NULL) {
        LOGE(" %s", "AAssetManager==NULL");
        return 0;
    }
    auto *layout = new LayoutNet();
    layout->setNumThread(numThread);
    if (!layout->initModel(mgr, name)) {
        delete layout;
        return 0;
    }
    return reinterpret_cast<jlong>(layout);
}

extern "C" JNIEXPORT void JNICALL
Java_com_benjaminwan_ocrlibrary_LayoutEngine_nativeRelease(JNIEnv *env, jobject thiz,
                                                           jlong handle) {
    delete reinterpret_cast<LayoutNet *>(handle);
}

/** 返回 float[6] 数组的数组：[labelId, score, x1, y1, x2, y2]，坐标为原图尺度。 */
extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_benjaminwan_ocrlibrary_LayoutEngine_nativeDetect(JNIEnv *env, jobject thiz, jlong handle,
                                                          jobject input, jfloat scoreThresh) {
    auto *layout = reinterpret_cast<LayoutNet *>(handle);
    if (layout == nullptr) return nullptr;
    jclass floatArrayClass = env->FindClass("[F");
    if (floatArrayClass == NULL) return NULL;

    std::vector<LayoutBox> boxes;
    cv::Mat imgRGBA, imgBGR;
    bitmapToMat(env, input, imgRGBA);
    cv::cvtColor(imgRGBA, imgBGR, cv::COLOR_RGBA2BGR);
    boxes = layout->detect(imgBGR, scoreThresh);

    jobjectArray result = env->NewObjectArray((jsize) boxes.size(), floatArrayClass, NULL);
    for (jsize i = 0; i < (jsize) boxes.size(); ++i) {
        jfloat values[6] = {(jfloat) boxes[i].labelId, boxes[i].score,
                            boxes[i].rect.x, boxes[i].rect.y,
                            boxes[i].rect.x + boxes[i].rect.width,
                            boxes[i].rect.y + boxes[i].rect.height};
        jfloatArray row = env->NewFloatArray(6);
        env->SetFloatArrayRegion(row, 0, 6, values);
        env->SetObjectArrayElement(result, i, row);
        env->DeleteLocalRef(row);
    }
    return result;
}

cv::Mat makePadding(cv::Mat &src, const int padding) {
    if (padding <= 0) return src;
    cv::Scalar paddingScalar = {255, 255, 255};
    cv::Mat paddingSrc;
    cv::copyMakeBorder(src, paddingSrc, padding, padding, padding, padding, cv::BORDER_ISOLATED,
                       paddingScalar);
    return paddingSrc;
}

extern "C"
JNIEXPORT jobject JNICALL
Java_com_benjaminwan_ocrlibrary_OcrEngine_nativeDetect(JNIEnv *env, jobject thiz, jlong handle,
                                                       jobject input, jobject output, jint padding,
                                                       jint maxSideLen, jfloat boxScoreThresh,
                                                       jfloat boxThresh, jfloat unClipRatio,
                                                       jboolean doAngle, jboolean mostAngle) {
    auto *engine = reinterpret_cast<OcrLite *>(handle);
    if (engine == nullptr) return nullptr;
    Logger("padding(%d),maxSideLen(%d),boxScoreThresh(%f),boxThresh(%f),unClipRatio(%f),doAngle(%d),mostAngle(%d)",
           padding, maxSideLen, boxScoreThresh, boxThresh, unClipRatio, doAngle, mostAngle);
    cv::Mat imgRGBA, imgBGR, imgOut;
    bitmapToMat(env, input, imgRGBA);
    cv::cvtColor(imgRGBA, imgBGR, cv::COLOR_RGBA2BGR);
    int originMaxSide = (std::max)(imgBGR.cols, imgBGR.rows);
    int resize;
    if (maxSideLen <= 0 || maxSideLen > originMaxSide) {
        resize = originMaxSide;
    } else {
        resize = maxSideLen;
    }
    resize += 2*padding;
    cv::Rect paddingRect(padding, padding, imgBGR.cols, imgBGR.rows);
    cv::Mat paddingSrc = makePadding(imgBGR, padding);
    //按比例缩小图像，减少文字分割时间
    ScaleParam s = getScaleParam(paddingSrc, resize);//例：按长或宽缩放 src.cols=不缩放，src.cols/2=长度缩小一半
    OcrResult ocrResult = engine->detect(paddingSrc, paddingRect, s, boxScoreThresh, boxThresh,
                                         unClipRatio, doAngle, mostAngle);

    cv::cvtColor(ocrResult.boxImg, imgOut, cv::COLOR_BGR2RGBA);
    matToBitmap(env, imgOut, output);

    return OcrResultUtils(env, ocrResult, output).getJObject();
}

extern "C" JNIEXPORT jdouble JNICALL
Java_com_benjaminwan_ocrlibrary_OcrEngine_nativeBenchmark(JNIEnv *env, jobject thiz, jlong handle,
                                                          jobject input, jint loop) {
    auto *engine = reinterpret_cast<OcrLite *>(handle);
    if (engine == nullptr) return 0;
    int padding = 50;
    int paddingRect= 0;
    float boxScoreThresh = 0.6;
    float boxThresh = 0.3;
    float unClipRatio = 2.0;
    bool doAngle = true;
    bool mostAngle = true;
    LOGI("padding(%d),paddingRect(%d),boxScoreThresh(%f),boxThresh(%f),unClipRatio(%f),doAngle(%d),mostAngle(%d)",
         padding, paddingRect, boxScoreThresh, boxThresh, unClipRatio, doAngle, mostAngle);
    cv::Mat imgRGBA, imgBGR, imgOut;
    bitmapToMat(env, input, imgRGBA);
    cv::cvtColor(imgRGBA, imgBGR, cv::COLOR_RGBA2BGR);
    cv::Rect originRect(padding, padding, imgBGR.cols, imgBGR.rows);
    cv::Mat src = makePadding(imgBGR, padding);
    //按比例缩小图像，减少文字分割时间
    ScaleParam s = getScaleParam(src, src.cols);//例：按长或宽缩放 src.cols=不缩放，src.cols/2=长度缩小一半

    LOGI("=====warmup=====");
    OcrResult result = engine->detect(src, originRect, s, boxScoreThresh, boxThresh,
                                      unClipRatio, doAngle, mostAngle);
    LOGI("dbNetTime(%f) detectTime(%f)\n", result.dbNetTime, result.detectTime);
    double dbTime = 0.0f;
    double detectTime = 0.0f;
    int loopCount = loop;
    for (int i = 0; i < loopCount; ++i) {
        LOGI("=====loop:%d=====", i + 1);
        OcrResult ocrResult = engine->detect(src, originRect, s, boxScoreThresh, boxThresh,
                                             unClipRatio, doAngle, mostAngle);
        LOGI("dbNetTime(%f) detectTime(%f)\n", ocrResult.dbNetTime, ocrResult.detectTime);
        dbTime += ocrResult.dbNetTime;
        detectTime += ocrResult.detectTime;
    }
    LOGI("=====result=====\n");
    double averageTime = detectTime / loopCount;
    LOGI("average dbNetTime=%fms, average detectTime=%fms\n", dbTime / loopCount,
         averageTime);
    return (jdouble) averageTime;
}
