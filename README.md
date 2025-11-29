# Spam Detector Chat Application

A robust real-time chat application with integrated spam detection using machine learning. The application features a server-client architecture with a Swing-based GUI, supporting multiple concurrent clients and server-side/client-side spam filtering.

## Features

- **Real-time Chat**: Multi-client chat application with server broadcasting
- **Spam Detection**: Machine learning-based spam classification using Naive Bayes
- **Dual Filtering**: 
  - Client-side filtering for outgoing messages
  - Server-side filtering for incoming messages
- **Message Bubbles**: Clean, modern UI with left/right aligned message bubbles
- **Spam Notifications**: Visual feedback when messages are blocked (red bubbles)
- **Chat Logging**: Automatic chat history saved to `chat_log.txt`
- **Message Deduplication**: Clients don't receive their own sent messages back
- **Confidence Scores**: Display ML model confidence for each classification
- **Avatar Support**: Profile images loaded from filesystem or classpath
- **Control Buttons**: Arrow, Call, Video, and More options buttons in header

## Project Structure

```
SpamDetector/
├── Client.java              # Client-side application with UI and local spam filter
├── Server.java              # Server application with multi-client handling
├── SpamFilter.java          # Standalone spam filter utility
├── ModelTraining.py         # ML model training script
├── predict.py               # Python spam prediction script
├── spam.csv                 # Dataset for training
├── spam_nb_model.pkl        # Trained Naive Bayes model
├── chat_log.txt             # Chat history log
├── Farwah.jpg               # Client avatar image
├── faraz2.jpg               # Server avatar image
└── README.md                # This file
```

## System Requirements

- **Java**: JDK 8 or higher
- **Python**: 3.6 or higher
- **Python Libraries**: scikit-learn, pandas, numpy, matplotlib, pickle

## Installation

### 1. Clone/Setup the Project

```bash
cd SpamDetector
```

### 2. Install Python Dependencies

```bash
pip install scikit-learn pandas numpy matplotlib
```

### 3. Train the ML Model (Optional)

If you need to retrain the model with the bundled dataset:

```bash
python ModelTraining.py
```

This will:
- Load the spam dataset from `spam.csv`
- Clean and preprocess text
- Train a Naive Bayes classifier with TF-IDF vectorization
- Include text cleaning in the pipeline
- Save the trained model to `spam_nb_model.pkl`
- Display accuracy metrics and confusion matrix

### 4. Compile Java Source

```bash
javac SpamDetector\*.java
```

## Usage

### Start the Server

```bash
java SpamDetector.Server
```

The server will:
- Listen on `localhost:6001`
- Display a Swing GUI with Faraz's profile
- Show all client connections and messages
- Apply spam filtering to incoming client messages
- Broadcast messages to all connected clients (excluding sender)

### Start Client(s)

```bash
java SpamDetector.Client
```

The client will:
- Connect to `localhost:6001`
- Display a Swing GUI with Farwah's profile
- Apply local spam filtering to outgoing messages
- Block high-confidence spam locally (red bubble)
- Display received messages from server and other clients
- Notify server when outgoing messages are blocked

### Launch Multiple Clients

Open additional PowerShell terminals and run:

```bash
java SpamDetector.Client
```

Each client runs independently and communicates through the server.

## Configuration

### Spam Threshold

Modify `SPAM_CONF_THRESHOLD` in `Server.java` and `Client.java`:

```java
private static final double SPAM_CONF_THRESHOLD = 0.80; // block if confidence >= threshold
```

- **0.80** (default): Block messages with ≥80% spam confidence
- Adjust lower for stricter filtering or higher for less filtering

### Python Command

If `python` is not in your PATH or you need `python3`:

```java
private static final String PYTHON_CMD = "python3"; // Change to python3 if needed
```

### Process Timeout

Modify timeout for Python prediction calls (in seconds):

```java
try { if (!p.waitFor(3, TimeUnit.SECONDS)) { p.destroyForcibly(); } } catch (InterruptedException ignored) {}
```

Default is **3 seconds**. Increase if model loading takes longer.

## Message Format

### Chat Protocol

Messages are transmitted as UTF-8 strings using Java `DataInputStream`/`DataOutputStream`.

### Spam Classification Output

The `predict.py` script outputs classification results in format:

```
label|confidence
```

Example:
```
spam|0.9943
ham|0.8521
```

### Special Messages

- **Blocked Outgoing from Client**: `[BLOCKED SPAM - Outgoing] <message>`
- **Blocked Outgoing from Server**: `[BLOCKED SPAM - Outgoing from Server]`
- **Blocked Outgoing from Client** (to others): `[BLOCKED SPAM - Outgoing from Client]`

## How Spam Detection Works

### Training (Python)

1. Load `spam.csv` dataset with categories (ham/spam)
2. Clean text: lowercase, remove URLs, punctuation, numbers
3. Build ML pipeline with:
   - Custom `FunctionTransformer` for text cleaning
   - `CountVectorizer` with English stopwords
   - `TfidfTransformer` for term frequency-inverse document frequency
   - `MultinomialNB` classifier (Naive Bayes)
4. Train on 80% of data, evaluate on 20%
5. Save complete pipeline to `spam_nb_model.pkl`

### Prediction (Python)

1. Load `spam_nb_model.pkl`
2. Clean input message (same preprocessing as training)
3. Run through pipeline
4. Extract prediction label and confidence score
5. Output in format: `label|confidence`
6. Handle errors gracefully with `error|0.0` fallback

## License

This project is provided as-is for educational purposes.

## Authors

- Zaheen Larik
- Faraz Thebo
- Ahmed Jamali

## Contact & Support

For issues or questions, please refer to the code comments and troubleshooting section above.

