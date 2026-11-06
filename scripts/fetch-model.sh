#!/usr/bin/env bash
#
# Downloads the sentence-embedding model used by Tier 2.
#
# The weights are deliberately NOT in git: ~90 MB of binary in history would make every
# clone slow forever, and the file never changes in a way diffs can express. This script
# is the reproducible substitute.
#
# Without it the gateway still starts, using the deterministic lexical fallback, and Tier 2
# refuses to serve anything. See EmbeddingConfig.
#
set -euo pipefail

MODEL_NAME="all-MiniLM-L6-v2"
HF_REPO="sentence-transformers/${MODEL_NAME}"
HF_BASE="https://huggingface.co/${HF_REPO}/resolve/main"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TARGET_DIR="${1:-${SCRIPT_DIR}/../models/${MODEL_NAME}}"

mkdir -p "${TARGET_DIR}"

# model.onnx is the quantised graph; the tokenizer files must come from the SAME revision.
# A tokenizer mismatched to its model produces embeddings that are wrong but never throw -
# the vectors are plausible and the similarity scores are meaningless.
FILES=(
  "onnx/model.onnx:model.onnx"
  "tokenizer.json:tokenizer.json"
  "tokenizer_config.json:tokenizer_config.json"
  "special_tokens_map.json:special_tokens_map.json"
  "vocab.txt:vocab.txt"
  "config.json:config.json"
)

echo "Fetching ${MODEL_NAME} into ${TARGET_DIR}"

for entry in "${FILES[@]}"; do
  remote="${entry%%:*}"
  local_name="${entry##*:}"
  dest="${TARGET_DIR}/${local_name}"

  if [[ -s "${dest}" ]]; then
    echo "  [skip] ${local_name} already present"
    continue
  fi

  echo "  [get ] ${remote}"
  if ! curl -fsSL --retry 3 --retry-delay 2 -o "${dest}" "${HF_BASE}/${remote}"; then
    rm -f "${dest}"
    if [[ "${local_name}" == "model.onnx" || "${local_name}" == "tokenizer.json" ]]; then
      echo "ERROR: ${remote} is required and could not be downloaded." >&2
      exit 1
    fi
    echo "  [warn] optional file ${remote} unavailable, continuing"
  fi
done

echo
echo "Done. Contents:"
ls -lh "${TARGET_DIR}"
echo
echo "Point the gateway at it with:"
echo "  EMBEDDING_MODEL_PATH=$(cd "${TARGET_DIR}" && pwd)"
