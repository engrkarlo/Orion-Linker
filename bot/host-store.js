'use strict'
const {DatabaseSync}=require('node:sqlite')
const {randomBytes}=require('node:crypto')
const path=require('node:path'),fs=require('node:fs')
const {createVault}=require('./pull')
const ID=/^\d{17,20}$/,SERVER=/^[a-f0-9]{16}$/
function createHostStore(dir){
 fs.mkdirSync(dir,{recursive:true,mode:0o700})
 const db=new DatabaseSync(path.join(dir,'settings.sqlite'))
 db.exec('PRAGMA journal_mode=WAL; PRAGMA busy_timeout=2500;')
 if(db.prepare('PRAGMA user_version').get().user_version>1){db.close();throw Error('Unsupported settings version')}
 db.exec(`BEGIN IMMEDIATE;
 CREATE TABLE IF NOT EXISTS settings(guild TEXT NOT NULL,server TEXT NOT NULL,config TEXT NOT NULL,PRIMARY KEY(guild,server));
 CREATE TABLE IF NOT EXISTS guild_keys(guild TEXT PRIMARY KEY,secret TEXT NOT NULL);
 CREATE TABLE IF NOT EXISTS case_messages(guild TEXT NOT NULL,case_id TEXT NOT NULL,channel TEXT NOT NULL,message TEXT NOT NULL,PRIMARY KEY(guild,case_id));
 PRAGMA user_version=1; COMMIT;`)
 const vault=createVault(path.join(dir,'configuration.key'))
 const base={audit:false,verification:false,moderation:false,verifyChannel:null,securityChannel:null,punishmentChannel:null,staffRoles:[],interval:2}
 function validate(g,s){if(typeof g!=='string'||!ID.test(g)||typeof s!=='string'||!SERVER.test(s))throw Error('Invalid scope')}
 function get(g,s){validate(g,s);const row=db.prepare('SELECT config FROM settings WHERE guild=? AND server=?').get(g,s);return row?{...structuredClone(base),...JSON.parse(row.config),guild:g,server:s}:null}
 function write(g,s,c){db.prepare('INSERT INTO settings VALUES(?,?,?) ON CONFLICT(guild,server) DO UPDATE SET config=excluded.config').run(g,s,JSON.stringify(c));return get(g,s)}
 function setup(g,s){
  validate(g,s);const existing=get(g,s)
  if(existing)return existing
  db.exec('BEGIN IMMEDIATE')
  try{
   const audit=vault.seal(randomBytes(32).toString('hex'),'audit:'+g+':'+s),control=vault.seal(randomBytes(32).toString('hex'),'control:'+g+':'+s)
   if(!db.prepare('SELECT 1 FROM guild_keys WHERE guild=?').get(g))db.prepare('INSERT INTO guild_keys VALUES(?,?)').run(g,vault.seal(randomBytes(32).toString('hex'),'network:'+g))
   const result=write(g,s,{...base,auditSecret:audit,controlSecret:control})
   db.exec('COMMIT');return result
  }catch(e){db.exec('ROLLBACK');throw e}
 }
 function update(g,s,patch){
  const c=get(g,s);if(!c)throw Error('Create setup first')
  const next={...c}
  for(const [k,v] of Object.entries(patch)){
   if(['audit','verification','moderation'].includes(k)){if(typeof v!=='boolean')throw Error('Invalid switch');next[k]=v}
   else if(['verifyChannel','securityChannel','punishmentChannel'].includes(k)){if(v!==null&&(typeof v!=='string'||!ID.test(v)))throw Error('Invalid channel');next[k]=v}
   else if(k==='staffRoles'){if(!Array.isArray(v)||v.length>10||v.some(id=>typeof id!=='string'||!ID.test(id)||id===g))throw Error('Invalid roles');next[k]=[...new Set(v)]}
   else if(k==='interval'){if(!Number.isInteger(v)||v<1||v>10)throw Error('Invalid polling interval');next[k]=v}
   else throw Error('Unsupported setting')
  }
  delete next.guild;delete next.server;return write(g,s,next)
 }
 function keys(g,s){
  const c=get(g,s);if(!c)throw Error('Not configured')
  const network=db.prepare('SELECT secret FROM guild_keys WHERE guild=?').get(g)
  return {audit:vault.open(c.auditSecret,'audit:'+g+':'+s),control:vault.open(c.controlSecret,'control:'+g+':'+s),network:vault.open(network.secret,'network:'+g)}
 }
 function list(){return db.prepare('SELECT guild,server FROM settings ORDER BY guild,server').all().map(r=>get(r.guild,r.server))}
 function caseMessage(g,id,channel,message){
  if(!ID.test(g)||!ID.test(channel)||!ID.test(message)||typeof id!=='string'||! /^[a-f0-9-]{36}$/.test(id))throw Error('Invalid message reference')
  db.prepare('INSERT INTO case_messages VALUES(?,?,?,?) ON CONFLICT(guild,case_id) DO UPDATE SET channel=excluded.channel,message=excluded.message').run(g,id,channel,message)
 }
 function getCaseMessage(g,id){return db.prepare('SELECT channel,message FROM case_messages WHERE guild=? AND case_id=?').get(g,id)||null}
 return {get,setup,update,keys,list,caseMessage,getCaseMessage,close:()=>db.close()}
}
module.exports={createHostStore}
