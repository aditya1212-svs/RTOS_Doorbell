import importlib.util
import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

import cv2
import numpy as np


ROOT = Path(__file__).parent
SPEC = importlib.util.spec_from_file_location("face_detector", ROOT / "face_detector.py")
worker = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(worker)


def image_file(image):
    handle = tempfile.NamedTemporaryFile(suffix=".png", delete=False)
    handle.close()
    ok, encoded = cv2.imencode(".png", image)
    assert ok
    Path(handle.name).write_bytes(encoded.tobytes())
    return Path(handle.name)


class FakeCascade:
    def __init__(self, boxes):
        self.boxes = boxes

    def empty(self):
        return False

    def detectMultiScale(self, image, scaleFactor, minNeighbors, minSize):
        return np.array(self.boxes, dtype=np.int32)


class FakeRecognition:
    @staticmethod
    def face_encodings(rgb, known_face_locations=None, num_jitters=1, model="small"):
        return [np.full(128, 0.1, dtype=np.float64)]

    @staticmethod
    def face_distance(known, query):
        return np.linalg.norm(np.asarray(known) - np.asarray(query), axis=1)


class FaceDetectorUnitTests(unittest.TestCase):
    def setUp(self):
        self.original_cascade = worker.CASCADE
        self.original_recognition = worker.face_recognition_library
        self.image = np.zeros((240, 320, 3), dtype=np.uint8)
        self.path = image_file(self.image)

    def tearDown(self):
        worker.CASCADE = self.original_cascade
        worker.face_recognition_library = self.original_recognition
        self.path.unlink(missing_ok=True)

    def test_one_face(self):
        worker.CASCADE = FakeCascade([(20, 30, 80, 90)])
        result = worker.detect_image(str(self.path))
        self.assertEqual(result["facesDetected"], 1)
        self.assertEqual(result["faces"], [{"x": 20, "y": 30, "width": 80, "height": 90}])

    def test_multiple_faces(self):
        worker.CASCADE = FakeCascade([(20, 30, 80, 90), (180, 40, 70, 80)])
        result = worker.detect_image(str(self.path))
        self.assertEqual(result["facesDetected"], 2)
        self.assertEqual(result["frameWidth"], 320)
        self.assertEqual(result["frameHeight"], 240)

    def test_invalid_image(self):
        invalid = self.path.with_suffix(".jpg")
        invalid.write_bytes(b"not-an-image")
        self.assertEqual(worker.detect_image(str(invalid))["error"], "INVALID_IMAGE")
        invalid.unlink()

    def test_embedding_and_known_match(self):
        worker.CASCADE = FakeCascade([(20, 30, 80, 90)])
        worker.face_recognition_library = FakeRecognition
        embedding = worker.embedding_image(str(self.path), {"x": 20, "y": 30, "width": 80, "height": 90})
        self.assertEqual(embedding["embeddingDimensions"], 128)
        result = worker.recognize_image(str(self.path), [{
            "personId": "person-1", "name": "John", "embedding": embedding["embedding"]
        }])
        self.assertTrue(result["recognitions"][0]["recognized"])
        self.assertEqual(result["recognitions"][0]["name"], "John")

    def test_registration_rejects_multiple_faces(self):
        worker.CASCADE = FakeCascade([(20, 30, 80, 90), (180, 40, 70, 80)])
        worker.face_recognition_library = FakeRecognition
        result = worker.embedding_image(str(self.path))
        self.assertEqual(result["error"], "REGISTRATION_REQUIRES_ONE_FACE")


class FaceDetectorProtocolTests(unittest.TestCase):
    def test_sequential_requests_and_errors(self):
        with tempfile.TemporaryDirectory() as directory:
            image = np.zeros((80, 100, 3), dtype=np.uint8)
            ok, encoded = cv2.imencode(".png", image)
            image_path = Path(directory) / "frame.png"
            image_path.write_bytes(encoded.tobytes())
            process = subprocess.Popen(
                [sys.executable, str(ROOT / "face_detector.py")],
                stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                text=True,
            )
            try:
                requests = [
                    {"requestId": "one", "imagePath": str(image_path)},
                    {"requestId": "two", "imagePath": str(image_path)},
                    {"requestId": "bad", "imagePath": str(Path(directory) / "missing.jpg")},
                ]
                for request in requests:
                    process.stdin.write(json.dumps(request) + "\n")
                process.stdin.close()
                responses = [json.loads(process.stdout.readline()) for _ in requests]
                self.assertEqual([response["requestId"] for response in responses], ["one", "two", "bad"])
                self.assertEqual(responses[0]["facesDetected"], 0)
                self.assertEqual(responses[1]["facesDetected"], 0)
                self.assertEqual(responses[2]["error"], "IMAGE_NOT_FOUND")
            finally:
                process.wait(timeout=5)
                if process.poll() is None:
                    process.kill()
                process.stdout.close()
                process.stderr.close()

    def test_accepts_java_serialized_null_fields(self):
        """The Java client serializes optional record fields as explicit JSON nulls."""
        with tempfile.TemporaryDirectory() as directory:
            image = np.zeros((80, 100, 3), dtype=np.uint8)
            ok, encoded = cv2.imencode(".png", image)
            image_path = Path(directory) / "frame.png"
            image_path.write_bytes(encoded.tobytes())
            process = subprocess.Popen(
                [sys.executable, str(ROOT / "face_detector.py")],
                stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                text=True,
            )
            try:
                # Shape matches FaceDetectionProcessManager.FaceDetectionWorkerRequest.
                request = {
                    "requestId": "java-1", "operation": "detect",
                    "imagePath": str(image_path), "minNeighbors": 5,
                    "face": None, "knownFaces": None, "threshold": None,
                }
                process.stdin.write(json.dumps(request) + "\n")
                process.stdin.flush()
                response = json.loads(process.stdout.readline())
                self.assertEqual(response["requestId"], "java-1")
                self.assertNotIn("error", response)
                self.assertEqual(response["frameWidth"], 100)
                self.assertEqual(response["frameHeight"], 80)
            finally:
                process.stdin.close()
                process.wait(timeout=5)
                if process.poll() is None:
                    process.kill()
                process.stdout.close()
                process.stderr.close()


if __name__ == "__main__":
    unittest.main()
