#!/bin/sh
# Rebuilds the Design and Integration Guide: the Word file from make-guide.js, then the PDF with LibreOffice.
# Needs Node.js, Python 3, Playwright and LibreOffice. Run ./build.sh first, so test-evidence/test-output.txt holds the latest test run (the guide's test table).
set -e
cd "$(dirname "$0")"
npm ci --no-audit --no-fund
python3 diagrams.py          # C4 and use-case diagrams (docs/diagrams/*.svg) from use-cases.json
node svg2png.js              # their PNG versions (needs Playwright with Chromium)
node make-guide.js
cd ..
soffice --headless --convert-to pdf --outdir . CrowdWatch_Design-and-Integration-Guide.docx >/dev/null
echo "Built docs/CrowdWatch_Design-and-Integration-Guide.docx and .pdf"
