'use strict'
const fs = require('node:fs/promises')
const path = require('node:path')
const http = require('node:http')
const { WebSocketServer, WebSocket } = require('ws')
const { SlashCommandBuilder, ContainerBuilder, MessageFlags, PermissionFlagsBits } = require('discord.js')
const { UUID, Codes, secretMatches, planRoles, validateConfig } = require('./core')
let runtime, starting
const privileged = ['Administrator','ManageGuild','ManageRoles','ManageChannels','BanMembers','KickMembers','ModerateMembers','ManageWebhooks','ManageMessages','MentionEveryone','ManageNicknames','ManageEvents','ManageThreads','MuteMembers','DeafenMembers','MoveMembers','ViewAuditLog'].map(p => PermissionFlagsBits[p]).filter(x => x !== undefined)
const key = (guild, user) => guild + ':' + user
const identity = (server, uuid) => server + ':' + uuid
function card(title, body) { return { flags: MessageFlags.IsComponentsV2, components: [new ContainerBuilder().setAccentColor(0x9b87f5).addTextDisplayComponents(t => t.setContent('## ' + title + '\n' + body + '\n-# OrionLink • Minecraft account linking'))], allowedMentions: { parse: [] } } }
class Runtime {
  constructor(client, manager) {
    this.client = client; this.manager = manager; this.dir = manager.dataDir; this.codes = new Codes(); this.links = new Map(); this.index = new Map(); this.sockets = new Map(); this.attempts = new Map(); this.pending = 0; this.tail = Promise.resolve(); this.closed = false; this.cursor = 0
  }
  log(s) { this.manager.log('[OrionLink] ' + s) }
  run(fn) {
    if (this.closed || this.pending >= 256) return Promise.reject(new Error('OrionLink is busy or unavailable.'))
    this.pending++
    const work = this.tail.then(fn); this.tail = work.catch(() => {}).finally(() => this.pending--); return work
  }
  async save() {
    const target = path.join(this.dir, 'links.json'), temp = target + '.tmp'
    await fs.writeFile(temp, JSON.stringify([...this.links.values()]), { mode: 0o600 }); await fs.rename(temp, target)
  }
  async start() {
    await fs.mkdir(this.dir, { recursive: true })
    const file = path.join(this.dir, 'config.json')
    let cfg
    try { cfg = JSON.parse(await fs.readFile(file, 'utf8')) } catch(e) {
      if (e.code !== 'ENOENT') throw e
      await fs.writeFile(file, JSON.stringify({ port: 8787, servers: [] }, null, 2), { flag:'wx', mode:0o600 }); throw new Error('Configure OrionLink config.json in its extension data directory and enable again.')
    }
    this.servers = validateConfig(cfg)
    try {
      const records = JSON.parse(await fs.readFile(path.join(this.dir, 'links.json'), 'utf8'))
      for (const r of records) {
        if (!UUID.test(r.uuid) || !Array.isArray(r.owned) || this.links.has(key(r.guild,r.user)) || this.index.has(identity(r.server,r.uuid))) throw new Error('Invalid or duplicate stored account')
        this.links.set(key(r.guild,r.user), r); this.index.set(identity(r.server,r.uuid),r)
      }
    } catch(e) { if (e.code !== 'ENOENT') throw e }
    this.wss = new WebSocketServer({ noServer: true, maxPayload: 32768, perMessageDeflate: false })
    this.http = http.createServer((req,res) => { res.writeHead(404); res.end() })
    this.http.on('upgrade',(req,socket,head) => {
      const s = this.servers.get(req.headers['x-orion-server'])
      if (this.closed || req.url !== '/orionlink' || !s || !secretMatches(s.secret, req.headers.authorization)) { socket.end('HTTP/1.1 401 Unauthorized\r\nConnection: close\r\n\r\n'); return }
      this.wss.handleUpgrade(req,socket,head,ws => this.attach(s,ws))
    })
    await new Promise((resolve,reject) => { this.http.once('error',reject); this.http.listen(cfg.port,'127.0.0.1',resolve) })
    this.timer = setInterval(() => this.maintenance(), 15_000); this.timer.unref()
    this.log('Listening on loopback; configure a TLS reverse proxy.')
  }
  attach(s, ws) {
    const old = this.sockets.get(s.id); if (old) old.terminate()
    this.sockets.set(s.id,ws); ws.alive = true; ws.on('pong',()=>ws.alive=true); ws.on('error',()=>ws.terminate())
    ws.on('close',()=>{ if(this.sockets.get(s.id)===ws) this.sockets.delete(s.id) })
    let count=0, window=Date.now()
    ws.on('message',(raw,binary)=>{
      if (this.closed || this.sockets.get(s.id)!==ws) return
      if (Date.now()-window>=1000) { window=Date.now();count=0 }
      if (binary || ++count>100) { ws.terminate(); return }
      try {
        const m=JSON.parse(raw.toString()); if(!UUID.test(m.uuid)) return
        if(m.type==='issue') { const code=this.codes.issue(s.id,s.guild,m.uuid); this.send(s.id,{type:'code',uuid:m.uuid,code}); return }
        if(m.type!=='snapshot' || !Array.isArray(m.groups) || m.groups.length>256 || m.groups.some(g=>typeof g!=='string'||g.length>100)) return
        if (!this.index.has(identity(s.id,m.uuid))) return
        this.run(async()=>{ if(this.sockets.get(s.id)!==ws) return; const record=this.index.get(identity(s.id,m.uuid)); if(record && !record.unlinking) await this.sync(s,record,m.groups) }).catch(e=>this.log('Sync deferred: '+String(e.message).slice(0,200)))
      } catch { /* Invalid payload or capacity limit; no secrets logged. */ }
    })
  }
  send(server,m) { const ws=this.sockets.get(server); if(ws?.readyState===WebSocket.OPEN && ws.bufferedAmount<65536) { ws.send(JSON.stringify(m),e=>{if(e)ws.terminate()}); return true } return false }
  maintenance() {
    if(this.closed) return
    for(const ws of this.sockets.values()) { if(!ws.alive){ws.terminate();continue} ws.alive=false;ws.ping() }
    this.codes.clean(); for(const [id,a] of this.attempts) if(a.until<=Date.now()) this.attempts.delete(id)
    const records=[...this.links.values()]; if(!records.length)return
    for(let n=0;n<Math.min(32,records.length);n++) { const r=records[this.cursor++%records.length]; if(!r.unlinking)this.send(r.server,{type:'snapshot_request',uuid:r.uuid}) }
    this.cursor%=records.length
  }
  async member(s,r) { const guild=await this.client.guilds.fetch(s.guild); return guild.members.fetch({ user:r.user, force:true }) }
  async sync(s,r,groups) {
    const member=await this.member(s,r), guild=member.guild
    const desired=planRoles(groups,s.mapping,s.verifiedRole,new Set(member.roles.cache.keys()),r.owned)
    for(const id of desired.remove) {
      const role=await guild.roles.fetch(id); if(!role){r.owned=r.owned.filter(x=>x!==id);await this.save();continue}
      if(!role.editable||role.managed) throw new Error('Cannot remove a managed role')
      await member.roles.remove(id,'OrionLink reconciliation');r.owned=r.owned.filter(x=>x!==id);await this.save()
    }
    if(desired.forget.length){r.owned=r.owned.filter(x=>!desired.forget.includes(x));await this.save()}
    for(const id of desired.add) {
      const role=await guild.roles.fetch(id)
      if(!role||!role.editable||role.managed||id===guild.id||privileged.some(bit=>role.permissions.has(bit))) {this.log('Skipped unsafe or unavailable mapped role '+id);continue}
      await member.roles.add(id,'OrionLink reconciliation'); if(!r.owned.includes(id)){r.owned.push(id);await this.save()}
    }
  }
  async link(guild,user,code) {
    const now=Date.now(), k=key(guild,user)
    let a=this.attempts.get(k)
    if(!a||a.until<=now){if(this.attempts.size>=8192)throw new Error('Verification is busy');a={count:0,until:now+60_000};this.attempts.set(k,a)}
    if(++a.count>5)throw new Error('Too many attempts. Try again in one minute.')
    if(this.links.has(k))throw new Error('You already have a linked account.')
    const v=this.codes.consume(code,guild)
    if(!v||this.index.has(identity(v.server,v.uuid)))throw new Error('Invalid, expired or already-linked code.')
    const s=this.servers.get(v.server);if(!s||s.guild!==guild)throw new Error('Server is no longer configured.')
    const r={guild,user,server:v.server,uuid:v.uuid,owned:[]}
    this.links.set(k,r);this.index.set(identity(r.server,r.uuid),r)
    try {await this.save()} catch(e){this.links.delete(k);this.index.delete(identity(r.server,r.uuid));throw e}
    this.send(r.server,{type:'snapshot_request',uuid:r.uuid});return r
  }
  async unlink(guild,user) {
    const k=key(guild,user),r=this.links.get(k);if(!r)throw new Error('No linked account.')
    const s=this.servers.get(r.server);if(!s||s.guild!==guild)throw new Error('Server configuration must be restored before unlinking.')
    r.unlinking=true;await this.save()
    const member=await this.member(s,r)
    for(const id of [...r.owned]){if(member.roles.cache.has(id))await member.roles.remove(id,'OrionLink account unlinked');r.owned=r.owned.filter(x=>x!==id);await this.save()}
    this.links.delete(k);this.index.delete(identity(r.server,r.uuid));try{await this.save()}catch(e){this.links.set(k,r);this.index.set(identity(r.server,r.uuid),r);throw e}
  }
  async stop() {
    this.closed=true;clearInterval(this.timer);for(const ws of this.sockets.values())ws.terminate();this.sockets.clear()
    if(this.wss)this.wss.close();if(this.http?.listening)await new Promise(resolve=>this.http.close(resolve));await this.tail
  }
}
async function start({client,manager}) {
  if(runtime)return;if(starting)return starting
  const r=new Runtime(client,manager);starting=(async()=>{try{await r.start();runtime=r}catch(e){await r.stop();throw e}})()
  try{await starting}finally{starting=null}
}
async function stop(){if(starting)await starting.catch(()=>{});const r=runtime;runtime=null;if(r)await r.stop()}
const data=new SlashCommandBuilder().setName('orionlink').setDescription('Minecraft account linking')
  .addSubcommand(s=>s.setName('link').setDescription('Link using your private Minecraft code').addStringOption(o=>o.setName('code').setDescription('Verification code').setRequired(true).setMinLength(12).setMaxLength(12)))
  .addSubcommand(s=>s.setName('unlink').setDescription('Unlink and remove roles granted by OrionLink'))
  .addSubcommand(s=>s.setName('status').setDescription('Show your linked account'))
async function command({interaction:i}) {
  if(!i.guildId){await i.reply({content:'Use this in a configured Discord server.',flags:MessageFlags.Ephemeral});return}
  await i.deferReply({flags:MessageFlags.Ephemeral})
  try {
    const r=runtime;if(!r)throw new Error('OrionLink is disabled.')
    const action=i.options.getSubcommand();let result
    if(action==='link'){const record=await r.run(()=>r.link(i.guildId,i.user.id,i.options.getString('code')));result=card('Account linked','Minecraft server: '+record.server+'\nRole synchronization requested.')}
    else if(action==='unlink'){await r.run(()=>r.unlink(i.guildId,i.user.id));result=card('Account unlinked','OrionLink-granted roles have been removed.')}
    else{const record=r.links.get(key(i.guildId,i.user.id));result=card('Link status',record?'Server: '+record.server+'\nConnection: '+(r.sockets.has(record.server)?'Online':'Offline')+'\nState: '+(record.unlinking?'Unlink pending':'Linked'):'Not linked. Run /link in Minecraft.')}
    await i.editReply(result)
  }catch(e){await i.editReply(card('OrionLink',String(e.message).slice(0,250))).catch(()=>{})}
}
module.exports={commands:[{data,run:command}],onReady:start,onUnload:stop,openSettings:async({interaction:i})=>{await i.reply({...card('OrionLink settings','Configure server credentials and role mappings in the extension data directory. Player commands: /orionlink link, /orionlink status, /orionlink unlink.'),flags:MessageFlags.IsComponentsV2|MessageFlags.Ephemeral})}}
