package com.aditya.rtos_doorbell.service;

import com.aditya.rtos_doorbell.dto.*;
import com.aditya.rtos_doorbell.entity.StoredFace;
import com.aditya.rtos_doorbell.repository.StoredFaceRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.time.Instant;
import java.util.*;

@Service
public class StoredFaceService {
    private final StoredFaceRepository repository;

    public StoredFaceService(StoredFaceRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public List<StoredFaceResponse> store(String deviceId, byte[] frame, String contentType,
                                          List<FaceBoundingBox> faces) {
        if (deviceId == null || deviceId.isBlank()) {
            throw new FaceDetectionException(HttpStatus.BAD_REQUEST, "deviceId is required when storing faces");
        }
        BufferedImage source = readImage(frame);
        String format = "image/png".equalsIgnoreCase(contentType) ? "png" : "jpg";
        String storedContentType = format.equals("png") ? "image/png" : "image/jpeg";
        List<StoredFaceResponse> storedFaces = new ArrayList<>();
        for (FaceBoundingBox face : faces) {
            BufferedImage crop = crop(source, face);
            byte[] image = encode(crop, format);
            StoredFace storedFace = repository.save(new StoredFace(deviceId, Instant.now(), face.x(), face.y(),
                    face.width(), face.height(), storedContentType, image));
            storedFaces.add(toResponse(storedFace));
        }
        return storedFaces;
    }

    @Transactional(readOnly = true)
    public List<StoredFaceResponse> list(String deviceId, int limit) {
        if (deviceId == null || deviceId.isBlank()) {
            throw new IllegalArgumentException("deviceId is required");
        }
        return repository.findByDeviceIdOrderByDetectedAtDesc(deviceId, PageRequest.of(0, Math.min(Math.max(limit, 1), 100)))
                .stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public StoredFace image(UUID id) {
        return repository.findById(id).orElseThrow(() -> new NoSuchElementException("Stored face not found: " + id));
    }

    private BufferedImage readImage(byte[] frame) {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(frame));
            if (image == null) throw new IOException("Unsupported image data");
            return image;
        } catch (IOException e) {
            throw new FaceDetectionException(HttpStatus.BAD_REQUEST, "Frame is not a valid image", e);
        }
    }

    private BufferedImage crop(BufferedImage source, FaceBoundingBox face) {
        int x = Math.max(0, face.x());
        int y = Math.max(0, face.y());
        int width = Math.min(face.width(), source.getWidth() - x);
        int height = Math.min(face.height(), source.getHeight() - y);
        if (width <= 0 || height <= 0) {
            throw new FaceDetectionException(HttpStatus.BAD_GATEWAY, "Face detector returned invalid coordinates");
        }
        return source.getSubimage(x, y, width, height);
    }

    private byte[] encode(BufferedImage image, String format) {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (!ImageIO.write(image, format, output)) throw new IOException("Unsupported output format");
            return output.toByteArray();
        } catch (IOException e) {
            throw new FaceDetectionException(HttpStatus.INTERNAL_SERVER_ERROR, "Unable to store detected face", e);
        }
    }

    private StoredFaceResponse toResponse(StoredFace face) {
        return new StoredFaceResponse(face.getId(), face.getDeviceId(), face.getDetectedAt(),
                new FaceBoundingBox(face.getX(), face.getY(), face.getWidth(), face.getHeight()),
                "/api/face/stored/" + face.getId() + "/image");
    }
}
