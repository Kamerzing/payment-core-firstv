// Isolated deployment drill. Requires Node 22.12+, Docker and a built application image.
import { spawnSync } from 'node:child_process';
import { randomBytes, createHash } from 'node:crypto';
import { mkdirSync, writeFileSync, appendFileSync, readFileSync } from 'node:fs';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createServer } from 'node:net';
import https from 'node:https';
import http from 'node:http';
import assert from 'node:assert/strict';

const root=resolve(dirname(fileURLToPath(import.meta.url)),'..');
const suffix=randomBytes(5).toString('hex'), project=`pc-smoke-${suffix}`;
const work=resolve(root,'build',project), secrets=resolve(work,'secrets');
mkdirSync(secrets,{recursive:true,mode:0o700});
const log=resolve(work,'commands.log');
// Shell variables override Compose env files. Keep deployment settings from
// redirecting this drill to the operator's images, secrets or database host.
const dockerEnv={...process.env};
for(const key of Object.keys(dockerEnv)) {
	if(/^(PAYMENT_|CADDY_|COMPOSE_)/i.test(key)||['ACME_EMAIL','POSTGRES_IMAGE'].includes(key.toUpperCase()))delete dockerEnv[key];
}
function docker(args,options={}) {
	const result=spawnSync('docker',args,{cwd:root,encoding:'utf8',timeout:600000,input:options.input,env:dockerEnv});
	appendFileSync(log,`\n> docker ${args.join(' ')}\n${result.stdout||''}${result.stderr||''}`);
	if(result.error) throw result.error;
	if(options.fail) assert.notEqual(result.status,0,'Command should have failed');
	else if(result.status!==0) throw new Error(`Docker command failed; see ${log}\n${(result.stderr||result.stdout||'').slice(-2000)}`);
	return options.fail?(result.stdout||'')+(result.stderr||''):result.stdout||'';
}
async function freePort() {
	const server=createServer();await new Promise((done,reject)=>{server.once('error',reject);server.listen(0,'127.0.0.1',done);});
	const port=server.address().port;await new Promise(done=>server.close(done));return port;
}
const imageOption=process.argv.indexOf('--image');
const image=imageOption>=0?process.argv[imageOption+1]:`payment-core:smoke-${suffix}`;
assert.ok(image,'Supply an image tag after --image');
const httpPort=await freePort(), httpsPort=await freePort();
for(const name of ['postgres_admin_password','migrator_password','app_password','backup_password','restic_password']) {
	writeFileSync(resolve(secrets,name),randomBytes(32).toString('hex')+'\n',{mode:0o444});
}
writeFileSync(resolve(secrets,'restic_config'),'RESTIC_REPOSITORY=/repository\n',{mode:0o444});
const env=resolve(work,'settings.env'), override=resolve(work,'override.json');
const subnet=80+Math.floor(Math.random()*150);
writeFileSync(env,`PAYMENT_IMAGE=${image}\nPAYMENT_BACKUP_IMAGE=payment-core-backup:smoke-${suffix}\nPAYMENT_SECRETS_DIR=${secrets.replaceAll('\\','/')}\nCADDY_SITE=localhost\nACME_EMAIL=drill@example.com\nCADDY_TLS_DIRECTIVE=tls internal\nPAYMENT_BIND=127.0.0.1\nPAYMENT_HTTP_PORT=${httpPort}\nPAYMENT_HTTPS_PORT=${httpsPort}\nPAYMENT_PROXY_SUBNET=172.29.${subnet}.0/24\nPAYMENT_PROXY_IP=172.29.${subnet}.2\nPAYMENT_BACKEND_PROXY_IP=172.29.${subnet}.3\n`);
writeFileSync(override,JSON.stringify({services:{
	'volume-init':{entrypoint:['sh','-ec','chown 70:70 /postgres /restore /repository; chown 10001:10001 /data /config'],volumes:['backup-repository:/repository']},
	backup:{volumes:['backup-repository:/repository']},restore:{volumes:['backup-repository:/repository']},
},volumes:{'backup-repository':{}}}));
const base=['compose','--env-file',env,'-f',resolve(root,'deploy/server/compose.yaml'),'-f',override,'--project-name',project];
const compose=(args,options)=>docker([...base,...args],options);
const sql=(service,user,database,command,fail=false)=>compose(['exec','-T',service,'psql','--no-psqlrc','-U',user,'-d',database,'-At','-v','ON_ERROR_STOP=1','-c',command],{fail}).trim();
let ca;
async function request(path,options={}) {
	return new Promise((done,reject)=>{
		const client=options.http?http:https;
		const request=client.request({hostname:'localhost',port:options.http?httpPort:httpsPort,path,ca,
			method:options.method||'GET',headers:options.headers||{},timeout:10000},response=>{
			const chunks=[];response.on('data',chunk=>chunks.push(chunk));response.on('end',()=>done({status:response.statusCode,headers:response.headers,body:Buffer.concat(chunks).toString()}));
		});
		request.on('error',reject);request.on('timeout',()=>request.destroy(new Error('HTTP timeout')));
		request.end(options.body);
	});
}
class Client {
	cookie='';
	async api(path,body,key,extra={}) {
		let headers={...extra,Cookie:this.cookie};
		if(body!==undefined) {
			const csrf=await this.api('/auth/csrf');headers={...headers,Cookie:this.cookie,[csrf.header_name]:csrf.token,'Content-Type':'application/json'};
		}
		if(key)headers['Idempotency-Key']=key;
		const result=await request('/api/v1'+path,{method:body===undefined?'GET':'POST',headers,body:body===undefined?undefined:JSON.stringify(body)});
		if(result.headers['set-cookie'])this.cookie=result.headers['set-cookie'][0].split(';')[0];
		assert.ok(result.status>=200&&result.status<300,`${path}: HTTP ${result.status}`);
		return result.status===204?null:JSON.parse(result.body);
	}
}
try {
	console.log(`Deployment drill: ${project}; artifacts: ${work}`);
	if(process.argv.includes('--build')) {
		console.log('Building the release image from the tested Gradle artifact…');
		const runtimeOption=process.argv.indexOf('--runtime-image');
		const runtimeArgs=runtimeOption<0?[]:['--build-arg',`JAVA_RUNTIME_IMAGE=${process.argv[runtimeOption+1]}`];
		docker(['build','-f','deploy/server/app.Dockerfile',...runtimeArgs,'-t',image,'build/libs']);
	}
	const config=JSON.parse(compose(['--profile','ops','--profile','restore','config','--format','json']));
	for(const service of ['backend','postgres','restore-postgres','migrate'])assert.equal(config.services[service].ports,undefined,`${service} must not publish ports`);
	for(const service of ['backend','postgres','caddy','backup','restore']) {
		assert.equal(config.services[service].read_only,true);assert.ok(config.services[service].cap_drop.includes('ALL'));
	}
	assert.equal(config.services.backend.environment.SPRING_DATASOURCE_PASSWORD,undefined);
	console.log('Building backup tools and starting the private HTTPS stack…');
	compose(['--profile','ops','build','backup']);
	compose(['up','-d','--wait','--wait-timeout','180']);
	const caFile=resolve(work,'local-ca.crt');compose(['cp','caddy:/data/caddy/pki/authorities/local/root.crt',caFile]);ca=readFileSync(caFile);
	const redirect=await request('/',{http:true});assert.equal(redirect.status,308);assert.ok(redirect.headers.location.startsWith('https://'));
	const panel=await request('/');assert.equal(panel.status,200);assert.ok(panel.body.includes('id="root"'));
	assert.ok(panel.headers['content-security-policy'].includes("default-src 'self'"));
	for(const path of ['/actuator/health/readiness','/actuator/env','/v3/api-docs','/swagger-ui/index.html'])assert.equal((await request(path)).status,404);
	const csrf=await request('/api/v1/auth/csrf');assert.match(csrf.headers['set-cookie'][0],/Secure/);assert.match(csrf.headers['set-cookie'][0],/HttpOnly/);
	console.log('Checking restricted database roles and financial commands through HTTPS…');
	for(const statement of ['CREATE TABLE forbidden(id int)','TRUNCATE ledger_posting','ALTER TABLE ledger_posting DISABLE TRIGGER ALL'])sql('postgres','payment_app','payment_core',statement,true);
	sql('postgres','payment_backup','payment_core',"DELETE FROM app_user",true);
	const password=randomBytes(24).toString('hex');
	compose(['run','--rm','--no-deps','-T','maintenance','--bootstrap-admin','--credentials-stdin'],{input:`drill.admin\n${password}\n`});
	const admin=new Client();await admin.api('/auth/login',{username:'drill.admin',password},undefined,{'X-Forwarded-For':'203.0.113.77'});
	await admin.api('/admin/users',{username:'drill.alice',password});
	const wallets=await admin.api('/admin/wallets'), aliceWallet=wallets.items.find(item=>item.owner_username==='drill.alice');
	await admin.api('/admin/deposits',{wallet_id:aliceWallet.id,amount_minor:'100000'},'drill-deposit');
	const alice=new Client();await alice.api('/auth/login',{username:'drill.alice',password},undefined,{'X-Forwarded-For':'203.0.113.88'});
	const addressBuckets=sql('postgres','postgres','payment_core',"SELECT count(*) FROM login_attempt_bucket WHERE bucket_key IN ('"+['203.0.113.77','203.0.113.88',`172.29.${subnet}.2`].map(ip=>createHash('sha256').update('address:'+ip).digest('hex')).join("','")+"')");
	assert.equal(addressBuckets,'0','Spoofed client addresses must not reach the rate limiter');
	const withdrawal={amount_minor:'100',expected_fee_minor:'1',expected_fee_basis_points:100};
	const original=await alice.api('/withdrawals',withdrawal,'drill-once');
	const hold=await alice.api('/holds',{type:'WITHDRAWAL',amount_minor:'1000',expected_fee_minor:'10',expected_fee_basis_points:100},'drill-hold');
	assert.equal((await admin.api('/admin/reconciliation')).consistent,true);
	console.log('Checking restart, persistent session, balances and idempotent replay…');
	compose(['restart','backend']);
	let ready=false;
	for(let i=0;i<90;i++) {
		try{if((await request('/api/v1/status')).status===200){ready=true;break;}}catch{}
		await new Promise(done=>setTimeout(done,1000));
	}
	assert.equal(ready,true,'Backend must return after restart');
	assert.equal((await alice.api('/withdrawals',withdrawal,'drill-once')).id,original.id);
	assert.equal((await alice.api('/holds/'+hold.id)).state,'ACTIVE');
	assert.equal((await alice.api('/wallets/me')).available_minor,'98889');
	console.log('Creating and verifying an encrypted backup, then restoring into an empty database…');
	compose(['--profile','ops','run','--rm','--no-deps','backup','init']);
	compose(['--profile','ops','run','--rm','--no-deps','backup','backup']);
	compose(['--profile','ops','run','--rm','--no-deps','backup','check']);
	const snapshotCount=()=>JSON.parse(compose(['--profile','ops','run','--rm','--no-deps','backup','snapshots','--json'])).length;
	const snapshotsBeforeFailure=snapshotCount();
	compose(['--profile','ops','run','--rm','--no-deps','-e','PGHOST=missing-database','backup','backup'],{fail:true});
	assert.equal(snapshotCount(),snapshotsBeforeFailure,'A failed dump must not create a snapshot');
	compose(['--profile','restore','up','-d','--wait','restore-postgres']);
	compose(['--profile','restore','run','--rm','--no-deps','restore','latest']);
	compose(['--profile','restore','run','--rm','--no-deps','verify-restore']);
	const financialSnapshot="SELECT json_build_object('operations',(SELECT count(*) FROM payment_operation),'postings',(SELECT count(*) FROM ledger_posting),'holds',(SELECT count(*) FROM payment_hold),'balances',(SELECT json_agg(row_to_json(a) ORDER BY id) FROM (SELECT id,balance_minor FROM ledger_account) a))";
	assert.equal(sql('postgres','postgres','payment_core',financialSnapshot),sql('restore-postgres','postgres','payment_restore',financialSnapshot));
	assert.match(compose(['--profile','restore','run','--rm','--no-deps','restore','latest'],{fail:true}),/not empty/);
	assert.match(compose(['--profile','restore','run','--rm','--no-deps','-e','PGHOST=postgres','restore','latest'],{fail:true}),/restricted/);
	console.log('Promoting the restored volume while preserving the original, then testing reconciliation failure…');
	compose(['--profile','restore','stop','caddy','backend','postgres','restore-postgres']);
	appendFileSync(env,'PAYMENT_DB_NAME=payment_restore\n');
	const promoted=(args,options)=>compose(['-f',resolve(root,'deploy/server/promote-restored.yaml'),...args],options);
	promoted(['up','-d','--wait','--wait-timeout','180','postgres','migrate','backend']);
	promoted(['run','--rm','--no-deps','maintenance','--reconcile-only']);
	sql('postgres','payment_app','payment_restore','DELETE FROM spring_session');
	promoted(['up','-d','--no-deps','caddy']);
	let promotedReady=false;
	for(let i=0;i<90;i++) {
		try {if((await request('/api/v1/status')).status===200){promotedReady=true;break;}} catch {}
		await new Promise(done=>setTimeout(done,1000));
	}
	assert.equal(promotedReady,true,'Promoted backend must become available through Caddy');
	assert.equal((await request('/api/v1/auth/me',{headers:{Cookie:alice.cookie}})).status,401);
	await alice.api('/auth/login',{username:'drill.alice',password});
	assert.equal((await alice.api('/withdrawals',withdrawal,'drill-once')).id,original.id);
	assert.equal((await alice.api('/wallets/me')).available_minor,'98889');
	sql('postgres','postgres','payment_restore',"ALTER TABLE ledger_account DISABLE TRIGGER USER; UPDATE ledger_account SET balance_minor=balance_minor+1 WHERE kind='SETTLEMENT'; ALTER TABLE ledger_account ENABLE TRIGGER USER");
	assert.match(promoted(['run','--rm','--no-deps','maintenance','--reconcile-only'],{fail:true}),/Ledger reconciliation failed/);
	writeFileSync(resolve(work,'result.json'),JSON.stringify({passed:true,project,image,checks:['https','cookie-csrf','private-endpoints','database-roles','spoofed-forwarding','restart','idempotency','encrypted-backup','failed-dump','restore','restore-guards','promotion','reconciliation-failure']},null,2));
	console.log('PASS: HTTPS, roles, restart, idempotency, backup and guarded restore with reconciliation.');
} catch(error) {
	try {compose(['ps','-a']);compose(['logs','--no-color','--tail','100']);} catch {}
	throw error;
} finally {
	if(process.argv.includes('--keep'))console.log(`Kept isolated project ${project}; compose files: ${work}`);
	else {
		assert.match(project,/^pc-smoke-[a-f0-9]+$/);
		compose(['--profile','ops','--profile','restore','down','--volumes','--remove-orphans']);
	}
}
