import pandas as pd
import numpy as np
import matplotlib.pyplot as plt
import pickle
import re
import string

from sklearn.model_selection import train_test_split
from sklearn.feature_extraction.text import CountVectorizer, TfidfTransformer
from sklearn.pipeline import Pipeline
from sklearn.preprocessing import FunctionTransformer
from sklearn.naive_bayes import MultinomialNB
from sklearn.metrics import classification_report, confusion_matrix, accuracy_score

# ==============================
# 1. Load dataset
# ==============================
print("Loading dataset...")

df = pd.read_csv("spam.csv", encoding="latin-1")

# Normalize column names if needed
df.columns = [c.lower() for c in df.columns]

if "v1" in df.columns and "v2" in df.columns:
    df = df.rename(columns={"v1": "category", "v2": "message"})
else:
    df = df.iloc[:, :2]
    df.columns = ["category", "message"]

df["category"] = df["category"].str.lower().str.strip()
df["message"] = df["message"].astype(str)

print(df.head())

# ==============================
# 2. Clean & preprocess text
# ==============================
def clean_text(text):
    text = text.lower()
    text = re.sub(r"http\S+", "", text)        # remove URLs
    text = text.translate(str.maketrans("", "", string.punctuation))  # remove punctuation
    text = re.sub(r"\d+", "", text)            # remove numbers
    text = text.strip()
    return text

df["cleaned"] = df["message"].apply(clean_text)

# ==============================
# 3. Class Distribution Plot
# ==============================
plt.figure(figsize=(5,3))
df["category"].value_counts().plot(kind="bar", color="purple")
plt.title("Class Distribution")
plt.xlabel("Category")
plt.ylabel("Count")
plt.tight_layout()
plt.show()

# ==============================
# 4. Split Dataset
# ==============================
# Keep the original messages as input: pipeline will include cleaning step
X = df["message"]
y = df["category"]

X_train, X_test, y_train, y_test = train_test_split(
    X, y, test_size=0.20, random_state=42, stratify=y
)

# ==============================
# 5. Build Naive Bayes Pipeline
# ==============================
pipeline = Pipeline([
    ("clean", FunctionTransformer(lambda texts: [clean_text(t) for t in texts], validate=False)),
    ("vect", CountVectorizer(stop_words="english")),
    ("tfidf", TfidfTransformer()),
    ("clf", MultinomialNB())
])

print("Training model...")
pipeline.fit(X_train, y_train)

# ==============================
# 6. Evaluate Model
# ==============================
preds = pipeline.predict(X_test)
acc = accuracy_score(y_test, preds)

print("\nAccuracy:", acc)
print("\nClassification Report:")
print(classification_report(y_test, preds))

# ==============================
# 7. Confusion Matrix Plot
# ==============================
cm = confusion_matrix(y_test, preds, labels=["ham", "spam"])
plt.figure(figsize=(4,3))
plt.imshow(cm, cmap="Blues")
plt.title("Confusion Matrix")
plt.xlabel("Predicted")
plt.ylabel("Actual")

for i in range(cm.shape[0]):
    for j in range(cm.shape[1]):
        plt.text(j, i, cm[i, j], ha="center", va="center", color="red")

plt.tight_layout()
plt.show()

# ==============================
# 8. Save Model as .pkl
# ==============================
MODEL_PATH = "spam_nb_model.pkl"
with open(MODEL_PATH, "wb") as f:
    pickle.dump(pipeline, f)

print(f"\nModel saved as: {MODEL_PATH}")

# ==============================
# 9. Predict User Input
# ==============================
while True:
    user_msg = input("\nEnter a message to classify (or 'exit'): ")

    if user_msg.lower() == "exit":
        break

    cleaned_msg = clean_text(user_msg)
    prediction = pipeline.predict([cleaned_msg])[0]
    print("\nPrediction →", prediction)
