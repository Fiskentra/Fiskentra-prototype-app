const path = require('path');
const fs = require('fs');
const { chromium } = require('playwright');

(async () => {
  const out = path.join(__dirname, 'previews');
  fs.mkdirSync(out, { recursive: true });
  const browser = await chromium.launch({
    headless: true,
    executablePath: 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe'
  });
  const page = await browser.newPage({ viewport: { width: 1640, height: 1100 }, deviceScaleFactor: 1 });
  page.on('pageerror', error => console.error(`PAGE_ERROR: ${error.message}`));
  page.on('console', message => {
    if (message.type() === 'error') console.error(`BROWSER_ERROR: ${message.text()}`);
  });
  await page.goto(`file:///${path.join(__dirname, 'fiskentra-10-concepts.html').replace(/\\/g, '/')}`, { waitUntil: 'load' });
  await page.screenshot({ path: path.join(out, 'overview.png'), fullPage: true });
  await page.addStyleTag({ content: '.concept-nav{position:static !important}' });
  const concepts = await page.locator('.concept').all();
  for (let index = 0; index < concepts.length; index += 1) {
    await concepts[index].screenshot({ path: path.join(out, `concept-${String(index + 1).padStart(2, '0')}.png`) });
  }
  const report = await page.evaluate(() => ({
    concepts: document.querySelectorAll('.concept').length,
    phones: document.querySelectorAll('.phone').length,
    horizontalOverflow: document.documentElement.scrollWidth > document.documentElement.clientWidth,
    title: document.title
  }));
  console.log(JSON.stringify(report));
  await browser.close();
})().catch(error => {
  console.error(error);
  process.exitCode = 1;
});
