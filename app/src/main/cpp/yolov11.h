//
// Created by tony on 2025/4/10.
//

#ifndef CAMERAOPENGLNATIVE_YOLOV11_H
#define CAMERAOPENGLNATIVE_YOLOV11_H


#include <android/log.h>

#include <algorithm>
#include <memory>
#include <opencv2/core/core.hpp>
#include <opencv2/highgui/highgui.hpp>
#include <opencv2/opencv.hpp>
#include <vector>

#include "layer.h"
#include "net.h"


#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>

#include <iostream>
#include <chrono>
#include <thread> // 用于模拟耗时操作

struct Object {
    cv::Rect_<float> rect;
    int label;
    float prob;
};

class YoloV11 {
private:
    ncnn::Net yolo;

public:
    YoloV11();
    bool Load(AAssetManager* mgr, const char* param,  const char* model);
    int Detect(const cv::Mat& bgr, std::vector<Object>& objects);
    int DetectNCNN(const ncnn::Mat& in, std::vector<Object>& objects);
};

std::string getLabelName(int labelIndex);


#endif //CAMERAOPENGLNATIVE_YOLOV11_H
