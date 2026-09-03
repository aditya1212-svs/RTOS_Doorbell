package com.aditya.rtos_doorbell.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record FaceRecognitionResponse(int facesDetected, List<FaceRecognitionFace> faces,
                                      int frameWidth, int frameHeight,
                                      Boolean recognized, String name, Double confidence) {
    public FaceRecognitionResponse(int facesDetected, List<FaceRecognitionFace> faces,
                                   int frameWidth, int frameHeight) {
        this(facesDetected, safeFaces(faces), frameWidth, frameHeight,
                singleRecognized(faces), singleName(faces), singleConfidence(faces));
    }

    public FaceRecognitionResponse(List<FaceRecognitionFace> faces, int frameWidth, int frameHeight) {
        this(faces == null ? 0 : faces.size(), faces, frameWidth, frameHeight);
    }

    private static List<FaceRecognitionFace> safeFaces(List<FaceRecognitionFace> faces) {
        return faces == null ? List.of() : List.copyOf(faces);
    }

    private static FaceRecognitionFace single(List<FaceRecognitionFace> faces) {
        return faces != null && faces.size() == 1 ? faces.get(0) : null;
    }

    private static Boolean singleRecognized(List<FaceRecognitionFace> faces) {
        FaceRecognitionFace face = single(faces);
        return face == null ? null : face.recognized();
    }

    private static String singleName(List<FaceRecognitionFace> faces) {
        FaceRecognitionFace face = single(faces);
        return face == null ? null : face.name();
    }

    private static Double singleConfidence(List<FaceRecognitionFace> faces) {
        FaceRecognitionFace face = single(faces);
        return face == null ? null : face.confidence();
    }
}
