#!/usr/bin/env bash
# Shomer server: rules + Cloud Functions → Firebase project jeremy-13bb9.
# Run in Google Cloud Shell (https://shell.cloud.google.com), signed in as the project owner:
#   curl -fsSL https://raw.githubusercontent.com/mzv7766-crypto/JRME/shomer/backend/deploy.sh | bash
set -euo pipefail
PROJECT=jeremy-13bb9
echo "▶ Shomer deploy → $PROJECT"
gcloud config set project "$PROJECT" >/dev/null
# APIs the functions need (enabling twice is harmless)
gcloud services enable cloudfunctions.googleapis.com cloudbuild.googleapis.com artifactregistry.googleapis.com \
  run.googleapis.com eventarc.googleapis.com pubsub.googleapis.com cloudscheduler.googleapis.com \
  androidpublisher.googleapis.com firebasestorage.googleapis.com >/dev/null
if ! gcloud storage buckets describe "gs://$PROJECT.firebasestorage.app" >/dev/null 2>&1; then
  echo "✖ Cloud Storage is not set up yet: Firebase console → Storage → Get started, then run this again."
  exit 1
fi
DIR=$(mktemp -d)
git clone -q --depth 1 -b shomer https://github.com/mzv7766-crypto/JRME.git "$DIR"
cd "$DIR/backend/functions" && npm install --no-audit --no-fund --omit=dev >/dev/null
cd "$DIR/backend"
npx --yes firebase-tools@14 deploy --project "$PROJECT" --only firestore:rules,storage,functions:shomer --force --non-interactive
NUM=$(gcloud projects describe "$PROJECT" --format='value(projectNumber)')
echo
echo "✅ Done."
echo "Google Play access – invite this e-mail in Play Console → Users and permissions:"
echo "   ${NUM}-compute@developer.gserviceaccount.com"
