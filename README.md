# Smart Doorbell

This project is a small smart-doorbell backend with two browser tools for local testing:

- `backend` - Spring Boot application
- `frontend` - phone/client view for notifications, summaries, audio, video, and face boxes

The simulator sends events to the backend and publishes its camera over WebRTC. The frontend subscribes to the backend and displays the result.

After WebRTC has connected, video travels directly between the two browsers. This means an existing video call can continue after Spring Boot is stopped. Creating a new connection, sending events, uploading snapshots, receiving notifications, fetching summaries, and backend-relayed audio still require Spring Boot to be running.

## Face detection

Face detection runs in `face-detector/face_detector.py`, which Spring Boot starts with `ProcessBuilder`. The worker reads newline-delimited JSON from stdin and writes one JSON response per request to stdout. It has no FastAPI/Uvicorn server and does not listen on port 8090.

Install the worker dependencies once:

```bash
cd face-detector
python3 -m venv .venv
. .venv/bin/activate
pip install -r requirements.txt
cd ..
```

Start Spring Boot (this starts the worker automatically):

```bash
./mvnw spring-boot:run
```

Check child-process status through Spring:

```bash
curl http://localhost:8080/api/face/health
```

Expected when ready: `{"available":true,"recognitionAvailable":true}`.

Submit a frame:

```bash
curl -X POST http://localhost:8080/api/face/detect -F frame=@face.jpg -H 'Accept: application/json'
```

In Postman, use **Body → form-data**, add a `frame` key of type **File**, and select a JPEG or PNG. Do not set `Content-Type` manually; Postman adds the multipart boundary.

An image containing two faces returns all bounding boxes and frame dimensions. A valid frame with no faces returns HTTP 200 and an empty `faces` list. Invalid/missing data returns 400, an oversized upload 413, an unsupported media type 415, too-frequent requests 429, an unavailable child process 503/502, and a detector timeout 504.

### Storing detected face crops

Detection is transient by default. To store each detected face crop in the existing JPA database, provide a device ID and set `store=true`:

```bash
curl -X POST 'http://localhost:8080/api/face/detect?deviceId=esp32-doorbell-01&store=true' -F frame=@face.jpg
```

The response adds `storedFaces`, containing an ID, timestamp, original bounding box, and image URL. Retrieve metadata with `GET /api/face/stored?deviceId=esp32-doorbell-01`, then load each returned image URL. The phone frontend provides **Detect & Store Current Frame** and **Load Stored Faces** controls.

Live simulator detections are published to `/topic/face/{deviceId}`. The phone subscribes to that destination and displays the latest boxes and stored crops. Recognition is separate from detection and runs only for a pending RING interaction.

## Face recognition

The long-running worker also supports `embedding` and `recognize` JSON operations. It uses the lightweight `face_recognition` library (128-value dlib embeddings) and compares the closest stored embedding with the configured distance threshold. Lower distance is a closer match; the default `face-recognition.threshold=0.6` recognizes matches at or below that distance. Embeddings are stored as explicit binary doubles in the H2 `face_embeddings` table and are never returned to the frontend.

Register a person and add samples:

```bash
curl -X POST http://localhost:8080/api/face/register \
  -F 'name=John' -F 'frame=@john-front.jpg'
curl -X POST http://localhost:8080/api/face/register \
  -F 'name=John' -F 'frame=@john-side.jpg'
```

Each image must contain exactly one face. Additional samples for an existing name are appended to the same `Person`; the dedicated form is also available at `/api/face/register/{personId}`. Manage people with `GET /api/person`, `POST /api/person` (JSON `{"name":"John"}`), and `DELETE /api/person/{id}`.

Test recognition directly:

```bash
curl -X POST http://localhost:8080/api/face/recognize -F frame=@visitor.jpg
```

One-face responses include compatibility fields (`recognized`, `name`, `confidence`) plus `faces`; multi-face responses contain one result per face. Each live `/api/face/detect` result that contains a face automatically starts one
recognition event for that device. A short device-scoped cooldown prevents the
simulator's sampled frames from creating duplicate events. Manual `RING` events
are still completed by the next `/api/frame` upload. Recognition updates the
`VisitorEvent` to `RECOGNIZED` or `UNKNOWN` and publishes exactly one
notification to `/topic/notify`:

```json
{"type":"VISITOR_RECOGNIZED","message":"John is at the door","name":"John"}
```

```json
{"type":"VISITOR_UNKNOWN","message":"Unknown person is at the door","name":null}
```

`GET /api/event` exposes visitor history. Recognition/model failures complete the pending interaction as unknown rather than leaving a RING permanently pending; direct registration/recognition requests return an explicit 4xx/5xx error.

### Configuration

```properties
face-detection.enabled=true
face-detection.python-command=face-detector/.venv/bin/python
face-detection.script-path=face-detector/face_detector.py
face-detection.working-directory=.
face-detection.max-image-bytes=5242880
face-detection.processing-threads=2
face-detection.interval-ms=200
face-detection.confidence-threshold=5
face-detection.startup-timeout-ms=10000
face-detection.request-timeout-ms=5000
face-recognition.enabled=true
face-recognition.threshold=0.6
face-recognition.request-timeout-ms=5000
```

`confidence-threshold` is sent to OpenCV Haar Cascade as `minNeighbors`; higher values are stricter. Set `VITE_FACE_DETECTION_INTERVAL_MS` in the simulator environment to control client-side sampling. Run backend tests with `./mvnw test`.

The default command uses the virtualenv created above. Set `FACE_DETECTION_PYTHON_COMMAND=python3` (or a Windows Python executable) when the dependencies are installed outside that virtualenv.

## Browser tools

```bash
cd esp32-simulator
npm run dev -- --port 5174
```

```bash
cd frontend
npm run dev -- --port 5173
```

This repository currently has no authentication implementation; when one is added, protect `/api/face/**` and `/topic/face/**` with the same device/user checks as the other APIs.
