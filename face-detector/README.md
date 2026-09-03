# Face detector worker

This is a long-running OpenCV worker managed by Spring Boot. It only locates faces; it does not identify people. It does not open an HTTP port.

Install its two dependencies and test it directly:

```bash
cd face-detector
python3 -m venv .venv
. .venv/bin/activate
pip install -r requirements.txt
cd ..
printf '%s' '{"requestId":"manual-1","imagePath":"/absolute/path/to/face.jpg","minNeighbors":5}' | face-detector/.venv/bin/python face-detector/face_detector.py
```

It reads one JSON object per line from stdin and writes exactly one JSON object per request to stdout. The image is supplied by a temporary file path. Diagnostics (including the ready message) go to stderr. Spring starts and stops this process automatically via `ProcessBuilder`; no Uvicorn/FastAPI server or port 8090 is required.
