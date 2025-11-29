import sys
import pickle
import re
import sys
import pickle
import re
import string

MODEL_PATH = "spam_nb_model.pkl"

def clean_text(text):
    text = text.lower()
    text = re.sub(r"http\S+", "", text)
    text = text.translate(str.maketrans("", "", string.punctuation))
    text = re.sub(r"\d+", "", text)
    text = text.strip()
    return text

if len(sys.argv) < 2:
    # consistent output format: label|confidence
    print("error|0.0")
    sys.exit(1)

raw = sys.argv[1]
text = clean_text(raw)

try:
    with open(MODEL_PATH, "rb") as f:
        model = pickle.load(f)
except Exception:
    print("error|0.0")
    sys.exit(1)

try:
    pred = model.predict([text])[0]
    probs = model.predict_proba([text])[0]
    prob = max(probs) if len(probs) > 0 else 0.0
    print(f"{pred}|{prob}")
except Exception:
    print("error|0.0")
    sys.exit(1)

text = sys.argv[1]
text = clean_text(text)

try:
    with open(MODEL_PATH, "rb") as f:
        model = pickle.load(f)
except FileNotFoundError:
    print("error|0.0")
    sys.exit(1)

pred = model.predict([text])[0]
probs = model.predict_proba([text])[0]
prob = max(probs) if len(probs) > 0 else 0.0

print(f"{pred}|{prob}")