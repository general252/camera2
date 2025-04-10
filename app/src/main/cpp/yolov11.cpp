//
// Created by tony on 2025/4/10.
//

#include "yolov11.h"


#define MAX_STRIDE 32

static const char *class_names[] = {"person", "bicycle", "car",
                                    "motorcycle", "airplane", "bus",
                                    "train", "truck", "boat",
                                    "traffic light", "fire hydrant", "stop sign",
                                    "parking meter", "bench", "bird",
                                    "cat", "dog", "horse",
                                    "sheep", "cow", "elephant",
                                    "bear", "zebra", "giraffe",
                                    "backpack", "umbrella", "handbag",
                                    "tie", "suitcase", "frisbee",
                                    "skis", "snowboard", "sports ball",
                                    "kite", "baseball bat", "baseball glove",
                                    "skateboard", "surfboard", "tennis racket",
                                    "bottle", "wine glass", "cup",
                                    "fork", "knife", "spoon",
                                    "bowl", "banana", "apple",
                                    "sandwich", "orange", "broccoli",
                                    "carrot", "hot dog", "pizza",
                                    "donut", "cake", "chair",
                                    "couch", "potted plant", "bed",
                                    "dining table", "toilet", "tv",
                                    "laptop", "mouse", "remote",
                                    "keyboard", "cell phone", "microwave",
                                    "oven", "toaster", "sink",
                                    "refrigerator", "book", "clock",
                                    "vase", "scissors", "teddy bear",
                                    "hair drier", "toothbrush"};

static inline float intersection_area(const Object &a, const Object &b) {
    cv::Rect_<float> inter = a.rect & b.rect;
    return inter.area();
}

static void qsort_descent_inplace(std::vector<Object> &objects, int left, int right) {
    int i = left;
    int j = right;
    float p = objects[(left + right) / 2].prob;

    while (i <= j) {
        while (objects[i].prob > p) i++;

        while (objects[j].prob < p) j--;

        if (i <= j) {
            // swap
            std::swap(objects[i], objects[j]);

            i++;
            j--;
        }
    }

#pragma omp parallel sections
    {
#pragma omp section
        {
            if (left < j) qsort_descent_inplace(objects, left, j);
        }
#pragma omp section
        {
            if (i < right) qsort_descent_inplace(objects, i, right);
        }
    }
}

static void qsort_descent_inplace(std::vector<Object> &objects) {
    if (objects.empty()) return;

    qsort_descent_inplace(objects, 0, objects.size() - 1);
}

static void nms_sorted_bboxes(const std::vector<Object> &faceobjects, std::vector<int> &picked,
                              float nms_threshold, bool agnostic = false) {
    picked.clear();

    const int n = faceobjects.size();

    std::vector<float> areas(n);
    for (int i = 0; i < n; i++) {
        areas[i] = faceobjects[i].rect.area();
    }

    for (int i = 0; i < n; i++) {
        const Object &a = faceobjects[i];

        int keep = 1;
        for (int j = 0; j < (int) picked.size(); j++) {
            const Object &b = faceobjects[picked[j]];

            if (!agnostic && a.label != b.label) continue;

            // intersection over union
            float inter_area = intersection_area(a, b);
            float union_area = areas[i] + areas[picked[j]] - inter_area;
            // float IoU = inter_area / union_area
            if (inter_area / union_area > nms_threshold) keep = 0;
        }

        if (keep) picked.push_back(i);
    }
}

static inline float sigmoid(float x) { return static_cast<float>(1.f / (1.f + exp(-x))); }

static inline float clampf(float d, float min, float max) {
    const float t = d < min ? min : d;
    return t > max ? max : t;
}

static void parse_yolov8_detections(float *inputs, float confidence_threshold, int num_channels,
                                    int num_anchors, int num_labels, int infer_img_width,
                                    int infer_img_height, std::vector<Object> &objects) {
    std::vector<Object> detections;
    cv::Mat output = cv::Mat((int) num_channels, (int) num_anchors, CV_32F, inputs).t();

    for (int i = 0; i < num_anchors; i++) {
        const float *row_ptr = output.row(i).ptr<float>();
        const float *bboxes_ptr = row_ptr;
        const float *scores_ptr = row_ptr + 4;
        const float *max_s_ptr = std::max_element(scores_ptr, scores_ptr + num_labels);
        float score = *max_s_ptr;
        if (score > confidence_threshold) {
            float x = *bboxes_ptr++;
            float y = *bboxes_ptr++;
            float w = *bboxes_ptr++;
            float h = *bboxes_ptr;

            float x0 = clampf((x - 0.5f * w), 0.f, (float) infer_img_width);
            float y0 = clampf((y - 0.5f * h), 0.f, (float) infer_img_height);
            float x1 = clampf((x + 0.5f * w), 0.f, (float) infer_img_width);
            float y1 = clampf((y + 0.5f * h), 0.f, (float) infer_img_height);

            cv::Rect_<float> bbox;
            bbox.x = x0;
            bbox.y = y0;
            bbox.width = x1 - x0;
            bbox.height = y1 - y0;
            Object object;
            object.label = max_s_ptr - scores_ptr;
            object.prob = score;
            object.rect = bbox;
            detections.push_back(object);
        }
    }
    objects = detections;
}


YoloV11::YoloV11() {
    printf("");
}


bool YoloV11::Load(AAssetManager* mgr, const char* param,  const char* model) {

#if NCNN_VULKAN
    yolo.opt.use_vulkan_compute = false;
#endif
    // /storage/emulated/0/Download/CameraOpenglH264/
    __android_log_print(ANDROID_LOG_DEBUG, "ncnn", "load model param");

    int rc = -2;
    rc = yolo.load_param(mgr, param);
    if (rc != 0) {
        __android_log_print(ANDROID_LOG_WARN, "ncnn", "load model param fail %d", rc);
        return false;
    }

    __android_log_print(ANDROID_LOG_DEBUG, "ncnn", "load model ...");
    rc = yolo.load_model(mgr, model);
    if (rc != 0) {
        __android_log_print(ANDROID_LOG_DEBUG, "ncnn", "load model fail %d", rc);
        return false;
    }

    __android_log_print(ANDROID_LOG_DEBUG, "ncnn", "load model end");
    return true;
}

int YoloV11::Detect(const cv::Mat &bgr, std::vector<Object> &objects) {
    const int target_size = 640;
    const float prob_threshold = 0.25f;
    const float nms_threshold = 0.45f;

    int img_w = bgr.cols;
    int img_h = bgr.rows;

    // letterbox pad to multiple of MAX_STRIDE
    int w = img_w;
    int h = img_h;
    float scale = 1.f;
    if (w > h) {
        scale = (float) target_size / w;
        w = target_size;
        h = h * scale;
    } else {
        scale = (float) target_size / h;
        h = target_size;
        w = w * scale;
    }

    ncnn::Mat in = ncnn::Mat::from_pixels_resize(bgr.data, ncnn::Mat::PIXEL_BGR2RGB, img_w, img_h, w, h);

    int wpad = (target_size + MAX_STRIDE - 1) / MAX_STRIDE * MAX_STRIDE - w;
    int hpad = (target_size + MAX_STRIDE - 1) / MAX_STRIDE * MAX_STRIDE - h;
    ncnn::Mat in_pad;
    ncnn::copy_make_border(in, in_pad, hpad / 2, hpad - hpad / 2, wpad / 2, wpad - wpad / 2,
                           ncnn::BORDER_CONSTANT, 114.f);

    const float norm_vals[3] = {1 / 255.f, 1 / 255.f, 1 / 255.f};
    in_pad.substract_mean_normalize(0, norm_vals);

    ncnn::Extractor ex = yolo.create_extractor();

    ex.input("in0", in_pad);

    std::vector<Object> proposals;

    // stride 32
    {
        // 获取开始时间点
        auto start = std::chrono::high_resolution_clock::now();

        ncnn::Mat out;
        ex.extract("out0", out);


        // 获取结束时间点
        auto end = std::chrono::high_resolution_clock::now();
        auto duration = std::chrono::duration_cast<std::chrono::milliseconds>(end - start);
        __android_log_print(ANDROID_LOG_DEBUG, "ncnn", " %d ms", duration.count());

        std::vector<Object> objects32;
        const int num_labels = sizeof(class_names) / sizeof(class_names[0]);
        parse_yolov8_detections((float *) out.data, prob_threshold, out.h, out.w, num_labels,
                                in_pad.w,
                                in_pad.h, objects32);
        proposals.insert(proposals.end(), objects32.begin(), objects32.end());
    }

    // sort all proposals by score from highest to lowest
    qsort_descent_inplace(proposals);

    // apply nms with nms_threshold
    std::vector<int> picked;
    nms_sorted_bboxes(proposals, picked, nms_threshold);

    int count = picked.size();

    objects.resize(count);
    for (int i = 0; i < count; i++) {
        objects[i] = proposals[picked[i]];

        // adjust offset to original unpadded
        float x0 = (objects[i].rect.x - (wpad / 2)) / scale;
        float y0 = (objects[i].rect.y - (hpad / 2)) / scale;
        float x1 = (objects[i].rect.x + objects[i].rect.width - (wpad / 2)) / scale;
        float y1 = (objects[i].rect.y + objects[i].rect.height - (hpad / 2)) / scale;

        // clip
        x0 = std::max(std::min(x0, (float) (img_w - 1)), 0.f);
        y0 = std::max(std::min(y0, (float) (img_h - 1)), 0.f);
        x1 = std::max(std::min(x1, (float) (img_w - 1)), 0.f);
        y1 = std::max(std::min(y1, (float) (img_h - 1)), 0.f);

        objects[i].rect.x = x0;
        objects[i].rect.y = y0;
        objects[i].rect.width = x1 - x0;
        objects[i].rect.height = y1 - y0;
    }

    return 0;
}


int YoloV11::DetectNCNN(const ncnn::Mat &in, std::vector<Object> &objects) {
    // auto in = ncnn::Mat::from_pixels_roi((const unsigned char*)ptr, ncnn::Mat::PIXEL_RGBA, width, height, 0,0,640,640);
    return 0;
}


#include <GLES2/gl2.h>  // OpenGL ES 2.0
#include <GLES/gl.h>    // OpenGL ES 1.1
#include <GLES3/gl3.h>

void readGL() {
    //glBindFramebuffer(GL_FRAMEBUFFER, fboId);
    //glReadPixels(0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, ptr);
    //glBindFramebuffer(GL_FRAMEBUFFER, 0);
}

void writeToFile(const char *path, const char *content, int size) {
    FILE *file = fopen(path, "wb+");
    if (file != nullptr) {
        fwrite(content, 1, size, file);
        fclose(file);
    }
}


void duration_ex() {
    // 获取开始时间点
    auto start = std::chrono::high_resolution_clock::now();

    // 获取结束时间点
    auto end = std::chrono::high_resolution_clock::now();
    auto duration = std::chrono::duration_cast<std::chrono::milliseconds>(end - start);
    __android_log_print(ANDROID_LOG_DEBUG, "ncnn", " %d ms", duration.count());
}

std::string getLabelName(int labelIndex){
    if (labelIndex <0 || labelIndex>=80){
        return "unknown";
    }

    return class_names[labelIndex];
}