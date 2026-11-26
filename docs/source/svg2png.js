// Renders docs/diagrams/*.svg (and the web-layer diagram) to PNG with the browser that Playwright brings.
// Run: node docs/source/svg2png.js   (needs Playwright with Chromium)
const fs = require('fs'), path = require('path');
const { chromium } = require('playwright');
(async () => {
  const dir = path.join(__dirname, '..', 'diagrams');
  const files = fs.readdirSync(dir).filter((f) => f.endsWith('.svg')).map((f) => path.join(dir, f));
  files.push(path.join(__dirname, '..', 'CrowdWatch_Web-Layer.svg'));
  const browser = await chromium.launch();
  for (const f of files) {
    const svg = fs.readFileSync(f, 'utf8');
    const w = +/width="(\d+)"/.exec(svg)[1], h = +/height="(\d+)"/.exec(svg)[1];
    const page = await browser.newPage({ viewport: { width: w, height: h }, deviceScaleFactor: 1.6 });
    await page.goto('file://' + f);
    await page.screenshot({ path: f.replace(/\.svg$/, '.png') });
    await page.close();
    console.log('png', path.basename(f));
  }
  await browser.close();
})();
