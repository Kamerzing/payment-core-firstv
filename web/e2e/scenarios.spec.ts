import { test, expect, type Page } from '@playwright/test';
import { mkdir } from 'node:fs/promises';
const password='Browser fixture password!';
async function login(page:Page,username:string) {await page.goto('/');await page.getByLabel('Логин',{exact:true}).fill(username);await page.getByLabel('Пароль',{exact:true}).fill(password);await page.getByRole('button',{name:'Войти →',exact:true}).click();await expect(page.getByRole('heading',{name:'Ваши финансы, на виду.'})).toBeVisible();}
async function api(page:Page,path:string,body?:unknown,key?:string) {return page.evaluate(async({path,body,key})=>{
	const headers:Record<string,string>={};if(body!==undefined){const csrf=await(await fetch('/api/v1/auth/csrf')).json();headers[csrf.header_name]=csrf.token;headers['Content-Type']='application/json';}if(key)headers['Idempotency-Key']=key;
	const result=await fetch('/api/v1'+path,{method:body===undefined?'GET':'POST',headers,body:body===undefined?undefined:JSON.stringify(body)});if(!result.ok)throw new Error('Fixture API '+result.status);return result.status===204?null:result.json();
},{path,body,key});}
async function confirm(page:Page) {await page.getByRole('dialog').getByRole('button',{name:'Подтвердить',exact:true}).click();await expect(page.getByRole('dialog')).not.toBeVisible();await expect(page.getByRole('status').filter({hasText:'Готово.'})).toBeVisible();}
async function fundAlice(page:Page) {
	await login(page,'browser.admin');
	const wallets=await api(page,'/admin/wallets');
	const wallet=wallets.items.find((item:{owner_username:string})=>item.owner_username==='browser.alice');
	await api(page,'/admin/deposits',{wallet_id:wallet.id,amount_minor:'100000'},crypto.randomUUID());
	await api(page,'/auth/logout',{});
}

test('user and administrator complete money and hold scenarios',async({page})=>{
	await login(page,'browser.admin');
	await page.getByRole('button',{name:'Пользователи',exact:true}).click();
	await page.getByLabel('Логин',{exact:true}).fill('browser.new');await page.getByLabel('Начальный пароль').fill(password);await page.getByRole('button',{name:'Создать пользователя',exact:true}).click();await expect(page.getByText('Пользователь и кошелёк созданы.')).toBeVisible();
	const users=await api(page,'/admin/users');const alice=users.items.find((u:{username:string})=>u.username==='browser.alice');const bob=users.items.find((u:{username:string})=>u.username==='browser.bob');
	const wallets=await api(page,'/admin/wallets');const aliceWallet=wallets.items.find((w:{owner_id:string})=>w.owner_id===alice.id);const bobWallet=wallets.items.find((w:{owner_id:string})=>w.owner_id===bob.id);
	await page.getByRole('button',{name:'Пополнения',exact:true}).click();await page.getByLabel('Кошелёк',{exact:true}).fill(aliceWallet.id);await page.getByLabel('Сумма, ₽',{exact:true}).fill('1000');await page.getByRole('button',{name:'Продолжить →'}).click();await confirm(page);
	await page.getByRole('button',{name:'Выйти',exact:true}).click();await login(page,'browser.alice');await expect(page.locator('.balance-number')).toContainText(/1\s000,00/);
	await mkdir('../docs/screenshots',{recursive:true});await page.screenshot({path:'../docs/screenshots/wallet-desktop.png',fullPage:true});
	await page.getByRole('button',{name:'Перевести и оплатить',exact:true}).click();await page.getByLabel('Кошелёк получателя',{exact:true}).fill(bobWallet.id);await page.getByLabel('Сумма, ₽',{exact:true}).fill('100');await page.getByRole('button',{name:'Рассчитать комиссию →'}).click();await expect(page.getByRole('dialog')).toContainText('1,00 ₽');await confirm(page);
	await page.getByRole('button',{name:'Резервы',exact:true}).click();await page.getByRole('button',{name:'Списание',exact:true}).click();await page.getByLabel('Сумма, ₽',{exact:true}).fill('20');await page.getByRole('button',{name:'Рассчитать комиссию →'}).click();await confirm(page);
	await page.locator('.hold-item').filter({hasText:'Активен'}).getByRole('button',{name:'Подтвердить',exact:true}).click();await confirm(page);await expect(page.locator('.hold-item').filter({hasText:'Подтверждён'})).toHaveCount(1);
	await page.getByLabel('Сумма, ₽',{exact:true}).fill('30');await page.getByRole('button',{name:'Рассчитать комиссию →'}).click();await confirm(page);await page.locator('.hold-item').filter({hasText:'Активен'}).getByRole('button',{name:'Отменить',exact:true}).click();await confirm(page);await expect(page.locator('.hold-item').filter({hasText:'Отменён'})).toHaveCount(1);
	await page.getByRole('button',{name:'История',exact:true}).click();await expect(page.getByRole('table')).toContainText('Списание из резерва');
	await page.setViewportSize({width:390,height:844});await page.getByRole('button',{name:'Обзор',exact:true}).click();await expect(page.locator('.balance-number')).toBeVisible();await page.screenshot({path:'../docs/screenshots/wallet-mobile.png',fullPage:true});await expect.poll(()=>page.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth)).toBe(true);await page.setViewportSize({width:1440,height:1000});
	await page.getByRole('button',{name:'Выйти',exact:true}).click();await login(page,'browser.admin');
	const history=await api(page,'/admin/operations');const captured=history.items.find((o:{type:string})=>o.type==='CAPTURE_WITHDRAWAL');
	await page.getByRole('button',{name:'Все операции',exact:true}).click();
	await page.getByRole('button',{name:`Открыть операцию ${captured.id}`,exact:true}).click();
	await expect(page.getByRole('dialog')).toContainText('20,20 ₽');
	await page.getByRole('dialog').getByRole('button',{name:'Закрыть',exact:true}).click();
	await page.getByRole('button',{name:'Возвраты',exact:true}).click();await page.getByLabel('Номер операции').fill(captured.id);await page.getByRole('button',{name:'Найти операцию',exact:true}).click();await page.getByRole('button',{name:'Вернуть полностью',exact:true}).click();await confirm(page);
	await page.getByRole('button',{name:'Аудит',exact:true}).click();await expect(page.getByRole('table')).toContainText('Возврат');await page.getByRole('button',{name:'Сверка',exact:true}).click();await expect(page.getByRole('heading',{name:'Все остатки совпадают'})).toBeVisible();await page.screenshot({path:'../docs/screenshots/admin-reconciliation.png',fullPage:true});
});

test('recovers ambiguous command after reload with the original key and prevents duplicate payment',async({page})=>{
	await fundAlice(page);
	await login(page,'browser.alice');
	const before=await api(page,'/wallets/me');let originalKey='';let interrupted=false;
	await page.route('**/api/v1/withdrawals',async route=>{originalKey=route.request().headers()['idempotency-key'];if(!interrupted){interrupted=true;await route.fetch();await route.abort('failed');}else await route.continue();});
	await page.getByRole('button',{name:'Перевести и оплатить',exact:true}).click();await page.getByRole('button',{name:'Списание',exact:true}).click();await page.getByLabel('Сумма, ₽',{exact:true}).fill('1');await page.getByRole('button',{name:'Рассчитать комиссию →'}).click();await page.getByRole('dialog').getByRole('button',{name:'Подтвердить',exact:true}).click();await expect(page.getByRole('button',{name:'Проверить результат',exact:true})).toBeVisible();const savedKey=originalKey;
	await page.reload();await expect(page.getByRole('dialog')).toBeVisible();await page.getByRole('button',{name:'Проверить результат',exact:true}).click();await expect(page.getByRole('dialog')).not.toBeVisible();expect(originalKey).toBe(savedKey);
	const after=await api(page,'/wallets/me');expect(BigInt(before.available_minor)-BigInt(after.available_minor)).toBe(101n);
	await page.getByRole('button',{name:'Выйти',exact:true}).click();await expect(page.getByRole('heading',{name:'Войти в кошелёк'})).toBeVisible();
});

test('keeps an ambiguous command through CSRF rejection and expired session until replay succeeds',async({page})=>{
	await fundAlice(page);
	await login(page,'browser.alice');
	const before=await api(page,'/wallets/me');const keys:string[]=[];
	await page.route('**/api/v1/withdrawals',async route=>{
		keys.push(route.request().headers()['idempotency-key']);
		if(keys.length===1){await route.fetch();await route.abort('failed');}
		else if(keys.length===2) await route.fulfill({status:403,json:{code:'CSRF_INVALID'}});
		else await route.continue();
	});
	await page.getByRole('button',{name:'Перевести и оплатить',exact:true}).click();
	await page.getByRole('button',{name:'Списание',exact:true}).click();
	await page.getByLabel('Сумма, ₽',{exact:true}).fill('1');
	await page.getByRole('button',{name:'Рассчитать комиссию →'}).click();
	await page.getByRole('dialog').getByRole('button',{name:'Подтвердить',exact:true}).click();
	await expect(page.getByRole('button',{name:'Проверить результат',exact:true})).toBeVisible();
	await page.getByRole('button',{name:'Проверить результат',exact:true}).click();
	await expect(page.getByRole('dialog').getByRole('alert')).toContainText('Обновите страницу');
	await api(page,'/auth/logout',{});
	await page.getByRole('button',{name:'Проверить результат',exact:true}).click();
	await expect(page.getByRole('heading',{name:'Войти в кошелёк'})).toBeVisible();
	await login(page,'browser.alice');
	await expect(page.getByRole('dialog')).toBeVisible();
	await page.getByRole('button',{name:'Проверить результат',exact:true}).click();
	await expect(page.getByRole('dialog')).not.toBeVisible();
	expect(keys).toHaveLength(4);expect(new Set(keys).size).toBe(1);
	const after=await api(page,'/wallets/me');
	expect(BigInt(before.available_minor)-BigInt(after.available_minor)).toBe(101n);
	expect(await page.evaluate(()=>Object.keys(sessionStorage).filter(key=>key.startsWith('payment-pending:')))).toEqual([]);
});

test('finds operation details, retries loading and hides foreign operations on mobile',async({page})=>{
	await login(page,'browser.admin');
	const wallets=await api(page,'/admin/wallets');
	const bob=wallets.items.find((item:{owner_username:string})=>item.owner_username==='browser.bob');
	const alice=wallets.items.find((item:{owner_username:string})=>item.owner_username==='browser.alice');
	const own=await api(page,'/admin/deposits',{wallet_id:bob.id,amount_minor:'12345'},crypto.randomUUID());
	const foreign=await api(page,'/admin/deposits',{wallet_id:alice.id,amount_minor:'1'},crypto.randomUUID());
	await api(page,'/auth/logout',{});await login(page,'browser.bob');
	let unavailable=true;
	await page.route(`**/api/v1/operations/${own.id}`,async route=>{
		if(unavailable){unavailable=false;await route.fulfill({status:503,json:{code:'RETRYABLE_FAILURE'}});}
		else await route.continue();
	});
	await page.getByRole('button',{name:`Открыть операцию ${own.id}`,exact:true}).click();
	await expect(page.getByRole('dialog').getByRole('alert')).toBeVisible();
	await page.getByRole('dialog').getByRole('button',{name:'Повторить',exact:true}).click();
	await expect(page.getByRole('dialog')).toContainText('123,45 ₽');
	await expect(page.getByRole('dialog')).toContainText(bob.id);
	await page.setViewportSize({width:390,height:844});
	await page.screenshot({path:'../docs/screenshots/operation-details-mobile.png',fullPage:true});
	await expect.poll(()=>page.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth)).toBe(true);
	await page.keyboard.press('Escape');await expect(page.getByRole('dialog')).not.toBeVisible();
	await page.getByLabel('Найти по номеру операции').fill(foreign.id);
	await page.getByRole('button',{name:'Открыть',exact:true}).click();
	await expect(page.getByRole('dialog').getByRole('alert')).toContainText('Операция не найдена');
});

test('does not show the previous history page when loading the next page fails',async({page})=>{
	await login(page,'browser.bob');
	const template={type:'DEPOSIT',state:'POSTED',source_wallet_id:null,target_wallet_id:null,original_operation_id:null,
		amount_minor:'100',fee_minor:'0',total_minor:'100',fee_basis_points:0,created_at:new Date().toISOString()};
	const firstPage=Array.from({length:20},()=>({...template,id:crypto.randomUUID()}));
	const lastPage={...template,id:crypto.randomUUID()};
	let unavailable=true;
	await page.route('**/api/v1/operations?*',async route=>{
		const offset=Number(new URL(route.request().url()).searchParams.get('offset'));
		if(offset===20&&unavailable) await route.fulfill({status:503,json:{code:'RETRYABLE_FAILURE'}});
		else await route.fulfill({json:{items:offset===0?firstPage:[lastPage],limit:20,offset}});
	});
	await page.getByRole('button',{name:'История',exact:true}).click();
	await expect(page.getByRole('table').getByRole('row')).toHaveCount(21);
	await page.getByRole('button',{name:'Далее',exact:true}).click();
	await expect(page.getByRole('alert')).toBeVisible();
	await expect(page.getByRole('table')).not.toBeVisible();
	await expect(page.getByText('Нет загруженных записей',{exact:true})).toBeVisible();
	unavailable=false;
	await page.getByRole('button',{name:'Обновить',exact:true}).click();
	await expect(page.getByRole('table')).toContainText(lastPage.id);
	await expect(page.getByText('Записи 21–21',{exact:true})).toBeVisible();
});
