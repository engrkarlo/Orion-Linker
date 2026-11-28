'use strict'
const fs=require('node:fs'),path=require('node:path'),{randomBytes}=require('node:crypto')
const {createSecurityStore,identity}=require('./security-store')
const {createHostStore}=require('./host-store'),{createVault,fetchBridge}=require('./pull')
const {drainAudit}=require('./audit-pull'),{deliverModeration}=require('./moderation-pull'),{createVerificationService}=require('./verification-pull')
const ui=require('./discord-ui')
const SNOW=/^\d{17,20}$/,SERVER=/^[a-f0-9]{16}$/
function createHostRuntime({client,manager,registry,dir=manager.dataDir,fetcher=fetchBridge,telemetryVault=createVault(),now=Date.now}){
 fs.mkdirSync(dir,{recursive:true,mode:0o700})
 const file=path.join(dir,'identity.key')
 try{fs.writeFileSync(file,randomBytes(32),{flag:'wx',mode:0o600})}catch(e){if(e.code!=='EEXIST')throw e}
 const store=createSecurityStore({file:path.join(dir,'security.sqlite'),secret:fs.readFileSync(file),now}),settings=createHostStore(dir)
 const health=new Map(),due=new Map()
 let stopped=false,timer=null,cursor=0,inflight=null,pruned=0
 const scope=(g,s)=>g+':'+s
 function servers(g){return manager.isGuildEnabled(g)?(registry.get(g)?.servers||[]).filter(s=>s.type==='java'&&s.bridgePull&&SERVER.test(s.id)):[]}
 function server(g,id){const s=servers(g).find(s=>s.id===id);if(!s)throw Error('Select a paired server in this guild.');return s}
 async function guild(g){return client.guilds.cache.get(g)||await client.guilds.fetch(g)}
 async function member(g,d){
  try{const m=await (await guild(g)).members.fetch({user:d,force:true});return m?.id===d&&!m.user?.bot?m:null}catch(e){if(Number(e.code)===10007)return null;throw e}
 }
 async function authorize(i,c,admin=false){
  const m=await member(i.guildId,i.user.id)
  if(!m)throw Error('Guild membership required.')
  if(m.permissions.has('ManageGuild'))return m
  if(!admin&&c?.staffRoles.some(r=>m.roles.cache.has(r)))return m
  throw Error('You do not have the required security permission.')
 }
 async function channel(g,id,restricted){
  if(!id)throw Error('Configure a destination channel first.')
  const ch=await (await guild(g)).channels.fetch(id)
  if(ch?.guildId!==g||ch.type!==0||typeof ch.send!=='function')throw Error('Select a text channel in this guild.')
  if(restricted&&ch.permissionsFor(ch.guild.roles.everyone)?.has('ViewChannel')!==false)throw Error('Security channels must deny View Channel to everyone.')
  return ch
 }
 const verification=createVerificationService({store,member,fetcher,now})
 function config(g,s,key){return {guild:g,server:s.id,origin:s.bridgePull.origin,token:telemetryVault.open(s.bridgePull.secret,scope(g,s.id)),key}}
 function syncTargets(g){for(const c of settings.list().filter(c=>c.guild===g)){store.register(g,c.server,c.moderation&&servers(g).some(s=>s.id===c.server))}}
 function ready(i){if(stopped||!i.guildId||!manager.isGuildEnabled(i.guildId))throw Error('OrionBridge is unavailable in this guild.')}
 async function refreshCase(g,id){
  const c=store.getCase(g,id),ref=settings.getCaseMessage(g,id)
  if(!c||!ref)return
  try{const s=server(g,c.server),ch=await channel(g,ref.channel,true),message=await ch.messages.fetch(ref.message);await message.edit(ui.caseCard(s,c,false))}catch{}
 }
 async function publishCase(g,s,c){
  const cfg=settings.get(g,s.id)
  try{const ch=await channel(g,cfg.punishmentChannel||cfg.securityChannel,true),m=await ch.send(ui.caseCard(s,c,false));settings.caseMessage(g,c.id,ch.id,m.id)}catch{}
 }
 async function pollOne(c){
  const g=c.guild,id=c.server,k=scope(g,id)
  if(stopped)return
  const s=servers(g).find(s=>s.id===id)
  store.register(g,id,Boolean(s&&c.moderation))
  if(!s){health.set(k,'Pairing unavailable');return}
  let keys
  try{keys=settings.keys(g,id)}catch{health.set(k,'Credential error');return}
  const statuses=[]
  async function step(name,enabled,fn){if(!enabled||stopped||!settings.get(g,id)?.[name.toLowerCase()]||!servers(g).some(x=>x.id===id&&x.bridgePull.secret===s.bridgePull.secret&&x.bridgePull.origin===s.bridgePull.origin))return;try{await fn();statuses.push(name+' OK')}catch{statuses.push(name+' unavailable')}}
  await step('Verification',c.verification,()=>verification.poll(config(g,s,keys.control)))
  await step('Moderation',c.moderation,async()=>{
   const results=await deliverModeration({...config(g,s,keys.control),store,fetcher})
   for(const result of results)await refreshCase(g,result.id)
  })
  await step('Audit',c.audit,async()=>{
   const result=await drainAudit({...config(g,s,keys.audit),store,fetcher})
   if(result.droppedSinceStartup)statuses.push('Dropped audit events: '+result.droppedSinceStartup)
   if(c.securityChannel){const ch=await channel(g,c.securityChannel,true);for(const e of store.unnotified(g,id,5)){const m=await ch.send(ui.evidence(s,e));store.markAlert(g,id,e.id,m.id)}}
  })
  health.set(k,statuses.join(' · ')||'Disabled')
 }
 async function tick(){
  if(stopped||inflight)return inflight
  inflight=(async()=>{
   if(now()-pruned>3600000){store.prune();pruned=now()}store.expire()
   const list=settings.list();if(!list.length)return
   const batch=[]
   for(let i=0;i<list.length&&batch.length<4;i++){
    const c=list[(cursor+i)%list.length],k=scope(c.guild,c.server)
    if((due.get(k)||0)>now())continue
    due.set(k,now()+c.interval*1000);batch.push(c)
   }
   cursor=(cursor+Math.max(1,batch.length))%list.length
   await Promise.allSettled(batch.map(pollOne))
  })().finally(()=>{inflight=null})
  return inflight
 }
 function start(){if(!timer&&!stopped){timer=setInterval(()=>tick().catch(()=>{}),2000);timer.unref?.();tick().catch(()=>{})}}
 async function stop(){if(stopped)return;stopped=true;clearInterval(timer);timer=null;await inflight;settings.close();store.close()}
 function dashboard(g,s){return ui.manager(s,settings.get(g,s.id),health.get(scope(g,s.id)))}
 async function respond(i,payload){if(i.deferred)return i.editReply({...payload,flags:payload.flags&~ui.PRIVATE});if(i.replied)return i.followUp(payload);return i.reply(payload)}
 async function defer(i){if(!i.deferred&&!i.replied)await i.deferReply({flags:ui.PRIVATE})}
 async function failure(i){try{await respond(i,ui.notice('Unable to complete action','Check permissions, pairing, configuration, or submitted values. No enforcement is assumed without a receipt.',true))}catch{}}
 function account(g,d){const l=store.findDiscord(g,d);return ui.notice('My account',l?'Linked identity: '+l.identity+' · One link per guild.':'No linked Minecraft identity in this guild.')}
 async function command(i){
  try{ready(i);await defer(i);if(i.options.getSubcommand()==='account'){if(!await member(i.guildId,i.user.id))throw Error('Guild membership required');return await respond(i,account(i.guildId,i.user.id))}await authorize(i,null,true);return await respond(i,ui.servers(servers(i.guildId)))}catch{await failure(i)}
 }
 function resolveIdentity(g,value){value=value.trim();if(SNOW.test(value)){const l=store.findDiscord(g,value);if(!l)throw Error('Unlinked account');return l.identity}return identity(value)}
 async function interaction(i){
  if(!i.customId?.startsWith('ob2:'))return false
  try{
   ready(i);const g=i.guildId,[action,id,object,type]=i.customId.slice(4).split(':')
   if(action==='overview'){await defer(i);await authorize(i,null,true);await respond(i,ui.servers(servers(g)));return true}
   const s=server(g,action==='server'?i.values?.[0]:id),sid=s.id
   let c=settings.get(g,sid)
   const field=k=>i.fields.getTextInputValue(k).trim()
   const publicActions=['link','claim','account','help']
   const adminActions=['server','manage','setup','channels','staff','staff-save','toggle-audit','toggle-verification','toggle-moderation','publish','approve','revoke','revoke-submit','channel-verifyChannel','channel-securityChannel','channel-punishmentChannel']
   const modalActions=['link','punish-type','revoke','compare']
   if(!modalActions.includes(action))await defer(i)
   if(publicActions.includes(action)){
    if(action==='account'){if(!await member(g,i.user.id))throw Error('Guild membership required');await respond(i,account(g,i.user.id));return true}
    if(!c?.verification||i.channelId!==c.verifyChannel)throw Error('Verification panel inactive')
    if(action==='help'){await respond(i,ui.notice('Linking help','Join Minecraft, generate a private code, submit it here, then confirm the Discord identity in Minecraft.'));return true}
    if(action==='link'){await i.showModal(ui.modal('claim:'+sid,'Link Minecraft account',ui.input('code','Private verification code','',32)));return true}
    await verification.claimCode(config(g,s,settings.keys(g,sid).control),i.user.id,field('code'))
    await respond(i,ui.notice('Confirmation requested','Return to Minecraft and confirm that the displayed Discord account is yours.'));return true
   }
   await authorize(i,c,adminActions.includes(action))
   if(action==='server'||action==='manage'){await respond(i,dashboard(g,s));return true}
   if(action==='setup'){
    c=settings.setup(g,sid);store.register(g,sid,c.moderation)
    const keys=settings.keys(g,sid)
    const yaml=["audit:","  enabled: true","  guild-id: '"+g+"'","  server-id: '"+sid+"'","  signing-key: '"+keys.audit+"'","control:","  enabled: true","  signing-key: '"+keys.control+"'","correlation:","  enabled: false","  confirm-real-client-addresses: false","  guild-key: '"+keys.network+"'"].join('\n')
    const fence=String.fromCharCode(96).repeat(3)
    await respond(i,ui.card([ui.text('## Private setup credentials\nMerge these sections into the existing config.yml. Preserve the telemetry token and other options. Restart Paper. Keep the verification gate disabled until polling works. Never share these credentials.'),ui.text(fence+'yaml\n'+yaml+'\n'+fence),ui.row(ui.btn('manage:'+sid,'Back'))]));return true
   }
   if(!c)throw Error('Setup required')
   if(action==='channels'){await respond(i,ui.channels(s,c));return true}
   if(action==='staff'){await respond(i,ui.staff(s,c));return true}
   if(action==='staff-save'){settings.update(g,sid,{staffRoles:i.values});await respond(i,dashboard(g,s));return true}
   if(action.startsWith('channel-')){
    const k=action.slice(8);if(!['verifyChannel','securityChannel','punishmentChannel'].includes(k))throw Error('Unknown channel setting')
    await channel(g,i.values[0],k!=='verifyChannel');c=settings.update(g,sid,{[k]:i.values[0]});await respond(i,ui.channels(s,c));return true
   }
   if(action.startsWith('toggle-')){
    const k=action.slice(7);if(!['audit','verification','moderation'].includes(k))throw Error('Unknown toggle')
    if(k==='verification'&&!c.verification&&!c.verifyChannel)throw Error('Set a verification channel')
    settings.update(g,sid,{[k]:!c[k]});syncTargets(g);await respond(i,dashboard(g,s));return true
   }
   if(action==='publish'){const ch=await channel(g,c.verifyChannel,false);await ch.send(ui.verifyPanel(s));await respond(i,ui.notice('Panel published','Players can use the configured verification channel.'));return true}
   if(action==='events'){await respond(i,ui.evidenceList(s,store.events(g,sid,25)));return true}
   if(action==='cases'){await respond(i,ui.caseList(s,store.listCases(g,sid,25)));return true}
   if(action==='compare'){await i.showModal(ui.modal('compare-submit:'+sid,'Compare linked accounts',ui.input('first','Discord ID or canonical MC identity','',100),ui.input('second','Discord ID or canonical MC identity','',100)));return true}
   if(action==='compare-submit'){
    const r=store.compare(g,sid,i.user.id,resolveIdentity(g,field('first')),resolveIdentity(g,field('second')))
    await respond(i,ui.notice('Network correlation',r.matches+' shared network fingerprints in the last 90 days. This does not establish common ownership or justify an automatic sanction.'));return true
   }
   if(['case-select','approve','revoke','revoke-submit'].includes(action)){
    const x=store.getCase(g,action==='case-select'?i.values[0]:object)
    if(!x||x.server!==sid&&x.scope!=='GUILD_MINECRAFT')throw Error('Unknown case')
    if(action==='approve'){
     if(!settings.get(g,x.server)?.moderation)throw Error('Moderation disabled')
     syncTargets(g);store.approve(g,x.id,i.user.id);await refreshCase(g,x.id)
    }
    if(action==='revoke'){await i.showModal(ui.modal('revoke-submit:'+sid+':'+x.id,'Revoke punishment',ui.input('reason','Reason','',500,2)));return true}
    if(action==='revoke-submit'){store.revoke(g,x.id,i.user.id,field('reason'));await refreshCase(g,x.id)}
    await respond(i,ui.caseCard(s,store.getCase(g,x.id)));return true
   }
   const e=store.getEvent(g,sid,action==='event-select'?i.values[0]:object)
   if(!e)throw Error('Unknown evidence')
   if(action==='event-select'||action==='evidence'){await respond(i,ui.evidence(s,e,true));return true}
   if(action==='export'){await respond(i,{...ui.notice('Evidence export','Private export. Do not distribute outside authorized staff.'),files:[{attachment:Buffer.from(JSON.stringify(e,null,2)),name:'evidence-'+e.id+'.json'}]});return true}
   if(!e.identity||!c.moderation)throw Error('No punishable identity or moderation disabled')
   if(action==='punish'){await respond(i,ui.punish(s,e));return true}
   if(action==='punish-type'){
    const t=i.values[0];if(!ui.types.includes(t))throw Error('Unsupported type')
    await i.showModal(ui.modal('punish-submit:'+sid+':'+e.id+':'+t,'Request punishment',ui.input('reason','Reason','',440,2),ui.input('duration','Duration, e.g. 1h, 7d, permanent',t==='PERMANENT_BAN'?'permanent':'1h',32),ui.input('scope','Scope: SERVER or GUILD','SERVER',6)));return true
   }
   if(action==='punish-submit'){
    const target=field('scope').toUpperCase()
    if(!ui.types.includes(type)||!['SERVER','GUILD'].includes(target))throw Error('Invalid punishment')
    const x=store.createCase(g,sid,{identity:e.identity,actor:i.user.id,type,reason:field('reason')+' [Evidence '+e.id+']',time:field('duration'),scope:target==='SERVER'?'MINECRAFT_SERVER':'GUILD_MINECRAFT'})
    await publishCase(g,s,x);await respond(i,ui.caseCard(s,x));return true
   }
   throw Error('Unknown action')
  }catch{await failure(i);return true}
 }
 return {start,stop,tick,command,interaction,settings,store}
}
module.exports={createHostRuntime}
