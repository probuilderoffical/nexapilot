import { chromium } from 'playwright';

const url = 'https://probuilderoffical.github.io/nexapilot/';
const browser = await chromium.launch({ headless: true });
const page = await browser.newPage();
const consoleErrors = [];
const pageErrors = [];

page.on('console', msg => {
  if (msg.type() === 'error') consoleErrors.push(msg.text());
});
page.on('pageerror', err => pageErrors.push(err.message));

const response = await page.goto(url, { waitUntil: 'networkidle', timeout: 60000 });
if (!response || !response.ok()) throw new Error(`Page load failed: ${response?.status()}`);

if ((await page.title()) !== 'NexaPilot') throw new Error(`Unexpected title: ${await page.title()}`);
await page.locator('#email').waitFor({ state: 'visible' });
await page.locator('#password').waitFor({ state: 'visible' });
await page.locator('#signInBtn').waitFor({ state: 'visible' });
await page.locator('#signUpBtn').waitFor({ state: 'visible' });

const manifestResponse = await page.request.get(new URL('./manifest.webmanifest', url).href);
if (!manifestResponse.ok()) throw new Error(`Manifest failed: ${manifestResponse.status()}`);

const supabaseHealth = await page.request.get('https://cdwcvmeruzjhjcahehqg.supabase.co/auth/v1/health');
if (!supabaseHealth.ok()) throw new Error(`Supabase auth health failed: ${supabaseHealth.status()}`);

if (pageErrors.length) throw new Error(`Page JS errors: ${pageErrors.join(' | ')}`);
if (consoleErrors.length) throw new Error(`Console errors: ${consoleErrors.join(' | ')}`);

console.log('PASS live NexaPilot smoke test');
console.log('URL:', page.url());
console.log('Title:', await page.title());
console.log('Supabase auth health:', supabaseHealth.status());

await browser.close();
