import { defineConfig } from '@playwright/test';
export default defineConfig({
	testDir: './e2e', fullyParallel: false, workers: 1, timeout: 90000,
	use: {baseURL:'http://127.0.0.1:18080',channel:'chromium',viewport:{width:1440,height:1000},trace:'retain-on-failure'},
	webServer: {
		command: process.platform==='win32' ? '.\\gradlew.bat --no-daemon --max-workers=2 browserTestServer' : './gradlew --no-daemon --max-workers=2 browserTestServer',
		cwd:'..', url:'http://127.0.0.1:18080/', timeout:240000, reuseExistingServer:false, stdout:'pipe',
	},
});
