'use strict'
// Central persistence primitives. Callers MUST authenticate the guild/server and
// Discord interaction before invoking these methods. No public HTTP or Discord routes.
const {DatabaseSync}=require('node:sqlite')
const {randomBytes,randomUUID,createHmac,timingSafeEqual}=require('node:crypto')
const GUILD=/^\d{17,20}$/, SERVER=/^[a-f0-9]{16}$/, IDENT=/^(?:java:[a-f0-9]{32}|bedrock:[1-9]\d{0,19}|authme:[a-z0-9_-]{1,32}:[a-z0-9_]{1,16})$/, ID=/^[a-f0-9-]{36}$/
const TYPES=new Set(['WARNING','KICK','MUTE','DISCORD_TIMEOUT','FREEZE','BUILD_RESTRICTION','WORLDEDIT_RESTRICTION','COMMAND_RESTRICTION','QUARANTINE','ROLE_SUSPENSION','TEMP_BAN','PERMANENT_BAN'])
const MC_ONLY=new Set(['MUTE','FREEZE','BUILD_RESTRICTION','WORLDEDIT_RESTRICTION','COMMAND_RESTRICTION','QUARANTINE'])
const MAX_DURATION=365*86400000
function duration(value){
  if(value==='permanent')return null
  if(typeof value!=='string'||value.length>32||!/^\d+[smhdw](?:\d+[smhdw])*$/.test(value))throw Error('Invalid duration')
  let total=0,seen=new Set()
  for(const [,n,u] of value.matchAll(/(\d+)([smhdw])/g)){
    if(seen.has(u))throw Error('Repeated duration unit');seen.add(u)
    total+=Number(n)*({s:1000,m:60000,h:3600000,d:86400000,w:604800000}[u])
  }
  if(!Number.isSafeInteger(total)||total<1000||total>MAX_DURATION)throw Error('Duration out of bounds')
  return total
}
function scope(guild,server){if(typeof guild!=='string'||!GUILD.test(guild)||typeof server!=='string'||!SERVER.test(server))throw Error('Invalid guild or server')}
function identity(v){if(typeof v!=='string'||!IDENT.test(v)||v==='java:'+'0'.repeat(32)||(v.startsWith('bedrock:')&&BigInt(v.slice(8))>0xffffffffffffffffn))throw Error('Invalid authenticated identity');return v}
function discord(v){if(typeof v!=='string'||!GUILD.test(v))throw Error('Invalid Discord identity');return v}
function text(v,max){if(typeof v!=='string'||!v.trim()||v.length>max||/[\u0000-\u001f\u007f]/.test(v))throw Error('Invalid text');return v.trim()}
function createSecurityStore({file,secret,now=Date.now}){
  if(!Buffer.isBuffer(secret)||secret.length!==32)throw Error('A persistent 32-byte secret is required')
  const db=new DatabaseSync(file)
  db.exec('PRAGMA foreign_keys=ON; PRAGMA busy_timeout=2500; PRAGMA journal_mode=WAL;')
  const version=db.prepare('PRAGMA user_version').get().user_version
  if(version>2){db.close();throw Error('Unsupported security database version')}
  db.exec(`BEGIN IMMEDIATE;
  CREATE TABLE IF NOT EXISTS servers(guild TEXT NOT NULL, server TEXT NOT NULL, PRIMARY KEY(guild,server));
  CREATE TABLE IF NOT EXISTS links(guild TEXT NOT NULL, identity TEXT NOT NULL, discord TEXT NOT NULL, created INTEGER NOT NULL, PRIMARY KEY(guild,identity),UNIQUE(guild,discord));
  CREATE TABLE IF NOT EXISTS challenges(guild TEXT NOT NULL,server TEXT NOT NULL,identity TEXT NOT NULL,session TEXT NOT NULL,code_hash TEXT NOT NULL UNIQUE,discord TEXT,expires INTEGER NOT NULL,PRIMARY KEY(guild,identity),FOREIGN KEY(guild,server) REFERENCES servers(guild,server));
  CREATE TABLE IF NOT EXISTS link_history(id TEXT PRIMARY KEY,guild TEXT NOT NULL,server TEXT NOT NULL,identity TEXT NOT NULL,discord TEXT NOT NULL,at INTEGER NOT NULL,action TEXT NOT NULL);
  CREATE TABLE IF NOT EXISTS cases(id TEXT PRIMARY KEY,guild TEXT NOT NULL,server TEXT NOT NULL,identity TEXT NOT NULL,discord TEXT,actor TEXT NOT NULL,type TEXT NOT NULL,reason TEXT NOT NULL,scope TEXT NOT NULL,created INTEGER NOT NULL,expires INTEGER,state TEXT NOT NULL,FOREIGN KEY(guild,server) REFERENCES servers(guild,server));
  CREATE INDEX IF NOT EXISTS cases_target ON cases(guild,identity,state,expires);
  CREATE TABLE IF NOT EXISTS evidence(guild TEXT NOT NULL,server TEXT NOT NULL,id TEXT NOT NULL,at INTEGER NOT NULL,kind TEXT NOT NULL,identity TEXT,discord TEXT,body TEXT NOT NULL,PRIMARY KEY(guild,server,id),FOREIGN KEY(guild,server) REFERENCES servers(guild,server));
  CREATE TABLE IF NOT EXISTS networks(guild TEXT NOT NULL,identity TEXT NOT NULL,fingerprint TEXT NOT NULL,last_seen INTEGER NOT NULL,PRIMARY KEY(guild,identity,fingerprint));
  CREATE INDEX IF NOT EXISTS networks_match ON networks(guild,fingerprint,last_seen);
  CREATE TABLE IF NOT EXISTS audit(id TEXT PRIMARY KEY,guild TEXT NOT NULL,server TEXT NOT NULL,actor TEXT NOT NULL,action TEXT NOT NULL,subject TEXT NOT NULL,at INTEGER NOT NULL);
  CREATE TABLE IF NOT EXISTS deliveries(case_id TEXT NOT NULL,guild TEXT NOT NULL,server TEXT NOT NULL,revision INTEGER NOT NULL,operation TEXT NOT NULL,state TEXT NOT NULL,receipt_at INTEGER,PRIMARY KEY(case_id,guild,server),FOREIGN KEY(case_id) REFERENCES cases(id),FOREIGN KEY(guild,server) REFERENCES servers(guild,server));
  PRAGMA user_version=2; COMMIT;`)
  const run=(sql,...args)=>db.prepare(sql).run(...args), get=(sql,...args)=>db.prepare(sql).get(...args), all=(sql,...args)=>db.prepare(sql).all(...args)
  function tx(fn){db.exec('BEGIN IMMEDIATE');try{const result=fn();db.exec('COMMIT');return result}catch(e){db.exec('ROLLBACK');throw e}}
  function server(g,s){scope(g,s);if(!get('SELECT 1 FROM servers WHERE guild=? AND server=?',g,s))throw Error('Unknown server')}
  const hash=(g,s,code)=>createHmac('sha256',secret).update('link\0'+g+'\0'+s+'\0'+code).digest('hex')
  const log=(g,s,a,action,subject)=>run('INSERT INTO audit VALUES(?,?,?,?,?,?,?)',randomUUID(),g,s,a,action,subject,now())
  function register(g,s){scope(g,s);run('INSERT OR IGNORE INTO servers VALUES(?,?)',g,s)}
  function linked(g,i){if(!GUILD.test(g))throw Error('Invalid guild');return get('SELECT discord,created FROM links WHERE guild=? AND identity=?',g,identity(i))||null}
  function issue(g,s,i,session){
    server(g,s);identity(i);text(session,100)
    return tx(()=>{
      if(linked(g,i))throw Error('Already linked')
      // 128-bit challenge, not a short guessable PIN. Never store plaintext.
      const code=randomBytes(16).toString('hex')
      run('DELETE FROM challenges WHERE expires<=? OR (guild=? AND identity=?)',now(),g,i)
      run('INSERT INTO challenges VALUES(?,?,?,?,?,NULL,?)',g,s,i,session,hash(g,s,code),now()+300000)
      return code
    })
  }
  function claim(g,s,code,d){
    server(g,s);discord(d)
    if(typeof code!=='string'||! /^[a-f0-9]{32}$/.test(code))throw Error('Invalid code')
    return tx(()=>{
      const row=get('SELECT identity,session,discord,expires FROM challenges WHERE guild=? AND server=? AND code_hash=?',g,s,hash(g,s,code))
      if(!row||row.expires<=now()||(row.discord&&row.discord!==d))throw Error('Invalid or already claimed code')
      if(get('SELECT 1 FROM links WHERE guild=? AND discord=?',g,d))throw Error('Discord account already linked')
      run('UPDATE challenges SET discord=? WHERE guild=? AND identity=?',d,g,row.identity)
      return {identity:row.identity,discord:d,session:row.session,expires:row.expires}
    })
  }
  function pendingClaim(g,s,i,session){
    server(g,s);identity(i);text(session,100)
    return get('SELECT discord,expires FROM challenges WHERE guild=? AND server=? AND identity=? AND session=? AND expires>? AND discord IS NOT NULL',g,s,i,session,now())||null
  }
  function confirm(g,s,i,session,d){
    server(g,s);identity(i);discord(d);text(session,100)
    return tx(()=>{
      const c=get('SELECT * FROM challenges WHERE guild=? AND server=? AND identity=?',g,s,i)
      if(!c||c.session!==session||c.discord!==d||c.expires<=now())throw Error('Confirmation does not match active session')
      run('INSERT INTO links VALUES(?,?,?,?)',g,i,d,now())
      run('INSERT INTO link_history VALUES(?,?,?,?,?,?,?)',randomUUID(),g,s,i,d,now(),'LINK')
      run('DELETE FROM challenges WHERE guild=? AND identity=?',g,i)
      log(g,s,d,'LINK',i)
      return linked(g,i)
    })
  }
  function createCase(g,s,{identity:i,actor,type,reason,time,scope:targetScope}){
    server(g,s);identity(i);discord(actor);reason=text(reason,500)
    if(!TYPES.has(type)||!['MINECRAFT_SERVER','GUILD_MINECRAFT','DISCORD','CROSS_PLATFORM'].includes(targetScope))throw Error('Unsupported case type or scope')
    if(MC_ONLY.has(type)&&['DISCORD','CROSS_PLATFORM'].includes(targetScope))throw Error('Minecraft-only restriction')
    if(type==='DISCORD_TIMEOUT'&&targetScope!=='DISCORD')throw Error('Timeout is Discord-only')
    if(type==='KICK'&&targetScope==='CROSS_PLATFORM')throw Error('Use separate kick cases')
    const ms=type==='KICK'?0:duration(time)
    if(type==='PERMANENT_BAN'&&ms!==null||type==='TEMP_BAN'&&ms===null||type==='DISCORD_TIMEOUT'&&(ms===null||ms>28*86400000))throw Error('Invalid duration for type')
    const id=randomUUID(),created=now(),expires=ms===null?null:created+ms
    // Case creation is not enforcement. All cases require approval in this foundation.
    tx(()=>{run('INSERT INTO cases VALUES(?,?,?,?,?,?,?,?,?,?,?,?)',id,g,s,i,linked(g,i)?.discord||null,actor,type,reason,targetScope,created,expires,'PENDING_APPROVAL');log(g,s,actor,'CASE_CREATED',id)})
    return get('SELECT * FROM cases WHERE guild=? AND id=?',g,id)
  }
  const localTypes=new Set(['MUTE','FREEZE','BUILD_RESTRICTION','QUARANTINE','TEMP_BAN','PERMANENT_BAN'])
  function approve(g,id,actor){
    discord(actor)
    return tx(()=>{
      const c=getCase(g,id)
      if(!c||c.state!=='PENDING_APPROVAL'||c.actor===actor||(c.expires!==null&&c.expires<=now()))throw Error('Case cannot be approved')
      if(!localTypes.has(c.type)||!['MINECRAFT_SERVER','GUILD_MINECRAFT'].includes(c.scope))throw Error('Enforcement type not implemented')
      const targets=c.scope==='MINECRAFT_SERVER'?[{server:c.server}]:all('SELECT server FROM servers WHERE guild=?',g)
      for(const target of targets)run("INSERT INTO deliveries VALUES(?,?,?,1,'APPLY','PENDING',NULL)",id,g,target.server)
      run("UPDATE cases SET state='PENDING_ENFORCEMENT' WHERE guild=? AND id=?",g,id)
      log(g,c.server,actor,'CASE_APPROVED',id)
      return getCase(g,id)
    })
  }
  function revoke(g,id,actor,reason){
    discord(actor);reason=text(reason,500)
    return tx(()=>{
      const c=getCase(g,id)
      if(!c||!['PENDING_APPROVAL','PENDING_ENFORCEMENT','PARTIAL','ACTIVE'].includes(c.state))throw Error('Case cannot be revoked')
      const pending=c.state==='PENDING_APPROVAL'
      run("UPDATE deliveries SET revision=revision+1,operation='REVOKE',state='PENDING',receipt_at=NULL WHERE guild=? AND case_id=?",g,id)
      run('UPDATE cases SET state=? WHERE guild=? AND id=?',pending?'REVOKED':'PENDING_REVOCATION',g,id)
      log(g,c.server,actor,'CASE_REVOKE_REQUESTED',id+' '+reason)
      return getCase(g,id)
    })
  }
  function pending(g,s,limit=50){
    server(g,s);if(!Number.isInteger(limit)||limit<1||limit>100)throw Error('Invalid limit')
    return all("SELECT d.case_id,d.revision,d.operation,c.identity,c.type,c.expires,c.reason FROM deliveries d JOIN cases c ON c.id=d.case_id AND c.guild=d.guild WHERE d.guild=? AND d.server=? AND d.state='PENDING' AND (d.operation='REVOKE' OR c.expires IS NULL OR c.expires>?) ORDER BY CASE d.operation WHEN 'REVOKE' THEN 0 ELSE 1 END,c.created LIMIT ?",g,s,now(),limit)
  }
  function acknowledge(g,s,id,revision){
    server(g,s)
    if(!Number.isInteger(revision)||revision<1)throw Error('Invalid revision')
    return tx(()=>{
      const c=getCase(g,id);if(!c)return false
      const d=get('SELECT * FROM deliveries WHERE case_id=? AND guild=? AND server=?',id,g,s)
      if(!d||d.revision!==revision)return false
      run("UPDATE deliveries SET state='PERSISTED',receipt_at=? WHERE case_id=? AND guild=? AND server=?",now(),id,g,s)
      const left=get("SELECT COUNT(*) AS n FROM deliveries WHERE guild=? AND case_id=? AND state='PENDING'",g,id).n
      const state=d.operation==='REVOKE'?(left?'PENDING_REVOCATION':'REVOKED'):(c.expires!==null&&c.expires<=now()?'EXPIRED':left?'PARTIAL':'ACTIVE')
      run('UPDATE cases SET state=? WHERE guild=? AND id=?',state,g,id)
      return true
    })
  }
  function expire(){
    return run("UPDATE cases SET state='EXPIRED' WHERE expires IS NOT NULL AND expires<=? AND state IN ('PENDING_APPROVAL','PENDING_ENFORCEMENT','PARTIAL','ACTIVE')",now()).changes
  }
  function getCase(g,id){if(!GUILD.test(g)||typeof id!=='string'||!ID.test(id))throw Error('Invalid case');return get('SELECT * FROM cases WHERE guild=? AND id=?',g,id)||null}
  function recordEvent(g,s,event){
    server(g,s)
    if(!event||typeof event.id!=='string'||!ID.test(event.id)||!Number.isSafeInteger(event.at)||event.at<0||event.at>now()+120000)throw Error('Invalid event')
    const kind=text(event.kind,60),i=event.identity==null?null:identity(event.identity)
    // Explicit narrow schema: avoid logging arbitrary payloads, IPs or command arguments.
    const safe={detail:text(event.detail,1000),source:text(event.source,60)}
    return run('INSERT OR IGNORE INTO evidence VALUES(?,?,?,?,?,?,?,?)',g,s,event.id,event.at,kind,i,i?linked(g,i)?.discord||null:null,JSON.stringify(safe)).changes===1
  }
  function events(g,s,limit=50){server(g,s);if(!Number.isInteger(limit)||limit<1||limit>100)throw Error('Invalid page size');return all('SELECT * FROM evidence WHERE guild=? AND server=? ORDER BY at DESC,id DESC LIMIT ?',g,s,limit)}
  // Caller passes only a keyed, guild-scoped fingerprint from authenticated login.
  function observeNetwork(g,s,i,fingerprint){
    server(g,s);identity(i);if(typeof fingerprint!=='string'||! /^[a-f0-9]{64}$/.test(fingerprint))throw Error('Invalid fingerprint')
    run('INSERT INTO networks VALUES(?,?,?,?) ON CONFLICT(guild,identity,fingerprint) DO UPDATE SET last_seen=excluded.last_seen',g,i,fingerprint,now())
  }
  function compare(g,s,actor,a,b){
    server(g,s);discord(actor);identity(a);identity(b);if(a===b)throw Error('Choose distinct identities')
    const row=get('SELECT COUNT(*) AS matches FROM networks a JOIN networks b ON a.guild=b.guild AND a.fingerprint=b.fingerprint WHERE a.guild=? AND a.identity=? AND b.identity=? AND a.last_seen>=? AND b.last_seen>=?',g,a,b,now()-90*86400000,now()-90*86400000)
    log(g,s,actor,'COMPARE',a+' '+b)
    return {matches:row.matches,commonOwnershipConfirmed:false}
  }
  function prune(){run('DELETE FROM challenges WHERE expires<=?',now());run('DELETE FROM networks WHERE last_seen<?',now()-90*86400000)}
  return {register,linked,issue,claim,pendingClaim,confirm,createCase,getCase,approve,revoke,pending,acknowledge,expire,recordEvent,events,observeNetwork,compare,prune,close:()=>db.close()}
}
module.exports={createSecurityStore,duration,identity}
