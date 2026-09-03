#!/usr/bin/env python3
"""Long-running stdin/stdout face-detection worker.

Stdout is reserved for one JSON response per JSON request. Diagnostics go to
stderr so the parent process can safely parse the protocol.
"""

import json
import os
import sys
from functools import lru_cache
from pathlib import Path

import cv2
import numpy as np

try:
    import face_recognition as face_recognition_library
except Exception as exc:  # Detection remains available when the optional model is not installed.
    face_recognition_library = None
    FACE_RECOGNITION_IMPORT_ERROR = str(exc)
else:
    FACE_RECOGNITION_IMPORT_ERROR = ""


MAX_IMAGE_BYTES = int(os.getenv("FACE_DETECTOR_MAX_IMAGE_BYTES", "5242880"))
DEFAULT_MIN_NEIGHBORS = int(os.getenv("FACE_DETECTOR_MIN_NEIGHBORS", "5"))
# Bounded LRU cache so an identical frame/box is not re-run through the
# (comparatively expensive) embedding model. Frames are keyed by image bytes and
# the detector box, so repeated recognition of the same visitor frame is cheap.
EMBEDDING_CACHE_SIZE = int(os.getenv("FACE_DETECTOR_EMBEDDING_CACHE_SIZE", "64"))
CASCADE = cv2.CascadeClassifier(
    cv2.data.haarcascades + "haarcascade_frontalface_default.xml"
)


def log(message: str) -> None:
    print(message, file=sys.stderr, flush=True)


def error_response(request_id: str, error: str) -> dict:
    return {"requestId": request_id, "error": error}


def detect_image(image_path: str, min_neighbors: int = DEFAULT_MIN_NEIGHBORS) -> dict:
    """Read and detect one image, returning protocol fields without requestId."""
    path = Path(image_path)
    if not path.is_file():
        return {"error": "IMAGE_NOT_FOUND"}
    try:
        if path.stat().st_size > MAX_IMAGE_BYTES:
            return {"error": "IMAGE_TOO_LARGE"}
        data = path.read_bytes()
    except OSError:
        return {"error": "IMAGE_READ_ERROR"}
    if not data:
        return {"error": "INVALID_IMAGE"}

    image = cv2.imdecode(np.frombuffer(data, dtype=np.uint8), cv2.IMREAD_GRAYSCALE)
    if image is None:
        return {"error": "INVALID_IMAGE"}
    if CASCADE.empty():
        return {"error": "OPENCV_INITIALIZATION_ERROR"}

    try:
        neighbors = max(1, min(20, int(min_neighbors)))
        detected = CASCADE.detectMultiScale(
            image, scaleFactor=1.1, minNeighbors=neighbors, minSize=(30, 30)
        )
    except Exception:
        log("OpenCV detection failed")
        return {"error": "DETECTION_ERROR"}

    height, width = image.shape[:2]
    boxes = []
    for x, y, box_width, box_height in detected:
        x, y, box_width, box_height = map(int, (x, y, box_width, box_height))
        # OpenCV normally guarantees this; enforce the wire-contract anyway.
        if x >= 0 and y >= 0 and box_width > 0 and box_height > 0 \
                and x + box_width <= width and y + box_height <= height:
            boxes.append({"x": x, "y": y, "width": box_width, "height": box_height})

    return {
        "facesDetected": len(boxes),
        "faces": boxes,
        "frameWidth": int(width),
        "frameHeight": int(height),
    }


def _read_image_bytes(image_path: str):
    """Read raw image bytes after applying the safe path/size checks used elsewhere."""
    path = Path(image_path)
    if not path.is_file():
        return None, "IMAGE_NOT_FOUND"
    try:
        if path.stat().st_size > MAX_IMAGE_BYTES:
            return None, "IMAGE_TOO_LARGE"
        data = path.read_bytes()
    except OSError:
        return None, "IMAGE_READ_ERROR"
    if not data:
        return None, "INVALID_IMAGE"
    return data, None


def _read_image(image_path: str, color: bool):
    """Decode an image (grayscale when color=False) using _read_image_bytes checks."""
    data, error = _read_image_bytes(image_path)
    if error:
        return None, error
    flag = cv2.IMREAD_COLOR if color else cv2.IMREAD_GRAYSCALE
    image = cv2.imdecode(np.frombuffer(data, dtype=np.uint8), flag)
    if image is None:
        return None, "INVALID_IMAGE"
    return image, None


def _read_color_image(image_path: str):
    """Read a color image after applying the same safe path/size checks as detection."""
    return _read_image(image_path, color=True)


def _embedding_for_crop(image, box):
    """Return a 128-dimensional face_recognition embedding for one detector box."""
    if face_recognition_library is None:
        return None, "RECOGNITION_UNAVAILABLE"
    try:
        x, y, width, height = (int(box.get(key, 0)) for key in ("x", "y", "width", "height"))
    except (AttributeError, TypeError, ValueError):
        return None, "INVALID_FACE_BOX"
    image_height, image_width = image.shape[:2]
    if x < 0 or y < 0 or width <= 0 or height <= 0 \
            or x + width > image_width or y + height > image_height:
        return None, "INVALID_FACE_BOX"
    # Haar boxes can be tighter than the landmark model's preferred context.
    # Add a small, clipped margin while preserving the original detector box as
    # the face location supplied to face_recognition.
    margin_x = max(2, int(round(width * 0.15)))
    margin_y = max(2, int(round(height * 0.15)))
    crop_x = max(0, x - margin_x)
    crop_y = max(0, y - margin_y)
    crop_right = min(image_width, x + width + margin_x)
    crop_bottom = min(image_height, y + height + margin_y)
    crop = image[crop_y:crop_bottom, crop_x:crop_right]
    rgb = cv2.cvtColor(crop, cv2.COLOR_BGR2RGB)
    try:
        # The crop comes from the Haar detector. Supplying its known location
        # avoids running a second face detector and keeps recognition tied to
        # the detector's coordinates.
        encodings = face_recognition_library.face_encodings(
            rgb, known_face_locations=[(y - crop_y, x + width - crop_x,
                                        y + height - crop_y, x - crop_x)],
            num_jitters=1, model="small"
        )
    except Exception:
        log("Face embedding generation failed")
        return None, "EMBEDDING_ERROR"
    if not encodings:
        return None, "NO_EMBEDDING"
    values = [float(value) for value in encodings[0]]
    if len(values) == 0 or not all(np.isfinite(values)):
        return None, "INVALID_EMBEDDING"
    return values, None


@lru_cache(maxsize=EMBEDDING_CACHE_SIZE)
def _embedding_cached(image_bytes, x, y, width, height):
    """Embed one detector box from cached image bytes, avoiding re-model cost.

    Keyed by the raw image bytes and the detector box so that re-recognizing the
    same frame (e.g. a retried visitor upload in a pending RING window) skips the
    expensive face_recognition inference entirely. Returns (values|None, error|None).
    """
    image = cv2.imdecode(np.frombuffer(image_bytes, dtype=np.uint8), cv2.IMREAD_COLOR)
    if image is None:
        return None, "INVALID_IMAGE"
    return _embedding_for_crop(image, {"x": x, "y": y, "width": width, "height": height})


def embedding_image(image_path: str, face_box=None,
                    min_neighbors: int = DEFAULT_MIN_NEIGHBORS) -> dict:
    """Generate an embedding for a supplied face crop or a single face image."""
    data, error = _read_image_bytes(image_path)
    if error:
        return {"error": error}
    if face_box is None:
        detection = detect_image(image_path, min_neighbors)
        if detection.get("error"):
            return detection
        boxes = detection.get("faces", [])
        if len(boxes) != 1:
            return {"error": "REGISTRATION_REQUIRES_ONE_FACE"}
        face_box = boxes[0]
    values, error = _embedding_cached(data, int(face_box["x"]), int(face_box["y"]),
                                      int(face_box["width"]), int(face_box["height"]))
    if error:
        return {"error": error}
    return {"embedding": values, "embeddingDimensions": len(values)}


def _unknown_recognition(box):
    return {
        "recognized": False,
        "name": None,
        "confidence": 0.0,
        "x": int(box["x"]),
        "y": int(box["y"]),
        "width": int(box["width"]),
        "height": int(box["height"]),
    }


def recognize_image(image_path: str, known_faces, min_neighbors: int = DEFAULT_MIN_NEIGHBORS,
                    threshold: float = 0.6) -> dict:
    """Detect every face and compare its embedding with registered embeddings."""
    if not isinstance(known_faces, list):
        return {"error": "INVALID_REQUEST"}
    detection = detect_image(image_path, min_neighbors)
    if detection.get("error"):
        return detection
    boxes = detection.get("faces", [])
    if not boxes:
        detection["recognitions"] = []
        return detection
    # With no registered people, the correct result is unknown and no model
    # inference is needed. This also lets a fresh installation use detection
    # before the optional recognition dependency is installed.
    if not known_faces:
        detection["recognitions"] = [_unknown_recognition(box) for box in boxes]
        return detection
    if face_recognition_library is None:
        return {"error": "RECOGNITION_UNAVAILABLE"}
    try:
        data, image_error = _read_image_bytes(image_path)
        if image_error:
            return {"error": image_error}
        references = []
        for known in known_faces:
            if not isinstance(known, dict):
                continue
            vector = known.get("embedding")
            if not isinstance(vector, list) or not vector:
                continue
            try:
                vector_array = np.asarray(vector, dtype=np.float64)
            except (TypeError, ValueError):
                continue
            if vector_array.ndim != 1 or not np.all(np.isfinite(vector_array)):
                continue
            references.append((str(known.get("personId", "")), str(known.get("name", "")), vector_array))
        if not references:
            detection["recognitions"] = [_unknown_recognition(box) for box in boxes]
            return detection

        dimension = references[0][2].shape[0]
        references = [item for item in references if item[2].shape == (dimension,)]
        if not references:
            detection["recognitions"] = [_unknown_recognition(box) for box in boxes]
            return detection
        known_vectors = np.asarray([item[2] for item in references], dtype=np.float64)
        recognitions = []
        for box in boxes:
            values, error = _embedding_cached(data, int(box["x"]), int(box["y"]),
                                              int(box["width"]), int(box["height"]))
            if error or values is None:
                recognitions.append(_unknown_recognition(box))
                continue
            query = np.asarray(values, dtype=np.float64)
            if query.shape != known_vectors.shape[1:]:
                recognitions.append(_unknown_recognition(box))
                continue
            distances = np.asarray(face_recognition_library.face_distance(known_vectors, query), dtype=np.float64)
            if distances.ndim != 1 or distances.size != len(references) or not np.all(np.isfinite(distances)):
                recognitions.append(_unknown_recognition(box))
                continue
            best_index = int(np.argmin(distances))
            distance = float(distances[best_index])
            confidence = max(0.0, min(1.0, 1.0 - distance))
            recognized = distance <= float(threshold)
            recognitions.append({
                "recognized": recognized,
                "name": references[best_index][1] if recognized else None,
                "confidence": confidence,
                "x": int(box["x"]),
                "y": int(box["y"]),
                "width": int(box["width"]),
                "height": int(box["height"]),
            })
        detection["recognitions"] = recognitions
        return detection
    except Exception:
        log("Face recognition comparison failed")
        return {"error": "RECOGNITION_ERROR"}


def handle(request: dict) -> dict:
    request_id = str(request.get("requestId") or "")
    if not request_id or request_id == "None":
        return error_response("", "INVALID_REQUEST")
    image_path = request.get("imagePath")
    if not isinstance(image_path, str) or not image_path:
        return error_response(request_id, "INVALID_REQUEST")
    operation = request.get("operation")
    # Java may serialize optional fields as explicit JSON nulls; treat them as absent.
    operation = "detect" if operation is None else operation
    if not isinstance(operation, str):
        return error_response(request_id, "INVALID_REQUEST")
    try:
        min_neighbors_raw = request.get("minNeighbors")
        min_neighbors = DEFAULT_MIN_NEIGHBORS if min_neighbors_raw is None else int(min_neighbors_raw)
        threshold_raw = request.get("threshold")
        threshold = 0.6 if threshold_raw is None else float(threshold_raw)
        if not np.isfinite(threshold) or threshold < 0:
            raise ValueError("threshold must be a non-negative number")
    except (TypeError, ValueError):
        return error_response(request_id, "INVALID_REQUEST")
    if operation == "detect":
        result = detect_image(image_path, min_neighbors)
    elif operation == "embedding":
        result = embedding_image(image_path, request.get("face"), min_neighbors)
    elif operation == "recognize":
        known_faces = request.get("knownFaces")
        result = recognize_image(image_path, known_faces if known_faces is not None else [],
                                 min_neighbors, threshold)
    else:
        result = error_response(request_id, "UNKNOWN_OPERATION")
    result["requestId"] = request_id
    return result


def main() -> int:
    if CASCADE.empty():
        log("OpenCV face cascade could not be loaded")
        return 1
    recognition_status = "available" if face_recognition_library is not None else "unavailable"
    if face_recognition_library is None:
        log(f"face recognition model unavailable: {FACE_RECOGNITION_IMPORT_ERROR}")
    log(f"face detector worker ready recognition={recognition_status}")
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        request = {}
        try:
            request = json.loads(line)
            if not isinstance(request, dict):
                raise ValueError("request must be a JSON object")
            response = handle(request)
        except (json.JSONDecodeError, ValueError, TypeError):
            response = error_response("", "INVALID_REQUEST")
        except Exception:
            log("Unexpected worker error")
            response = error_response(str(request.get("requestId", "")), "WORKER_ERROR")
        print(json.dumps(response, separators=(",", ":")), flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
