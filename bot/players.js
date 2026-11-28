'use strict'

const PAGE_SIZE = 5
// Bound unusually large or malicious status responses, not the visible page size.
const MAX_PLAYERS = 2000
const UUID = /^[a-f0-9]{32}$/
const JAVA_NAME = /^[a-zA-Z0-9_]{1,16}$/
const BEDROCK_NAME = /^[a-zA-Z0-9_ ]{1,16}$/
function cleanUuid(value) {
  if(typeof value !== 'string')return ''
  if(!/^(?:[a-f0-9]{32}|[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12})$/i.test(value))return ''
  const uuid=value.replace(/-/g,'').toLowerCase()
  return /^0+$/.test(uuid)?'':uuid
}
function isFloodgate(uuid) {return UUID.test(uuid)&&uuid.startsWith('0000000000000000')&&!/^0+$/.test(uuid)}
function dashed(uuid){return uuid.slice(0,8)+'-'+uuid.slice(8,12)+'-'+uuid.slice(12,16)+'-'+uuid.slice(16,20)+'-'+uuid.slice(20)}
function normalizePlayers(players) {
  const source=Array.isArray(players?.list)&&players.list.length?players.list:Array.isArray(players?.sample)?players.sample:[]
  const roster=[], seen=new Set()
  for(const item of source){
    const name=String(typeof item==='string'?item:item?.name_clean||item?.name||'').replace(/§[0-9a-fk-or]/gi,'').replace(/[\u0000-\u001f\u007f]/g,'').trim().slice(0,80)
    if(!name)continue
    const uuid=cleanUuid(item?.uuid||item?.id)
    const key=name.toLowerCase()
    if(seen.has(key))continue
    seen.add(key)
    roster.push({name,uuid})
    if(roster.length>=MAX_PLAYERS)break
  }
  // Stable ordering keeps names on predictable pages between refreshes.
  roster.sort((a,b)=>a.name.localeCompare(b.name,undefined,{sensitivity:'base'}))
  return {roster, rosterLimited:source.length>MAX_PLAYERS && roster.length>=MAX_PLAYERS}
}
function pageOf(result,requested=0){
  const roster=result.online?(Array.isArray(result.roster)?result.roster:normalizePlayers({list:result.list}).roster):[]
  const pages=Math.max(1,Math.ceil(roster.length/PAGE_SIZE))
  const page=Math.min(pages-1,Math.max(0,Number.isSafeInteger(requested)?requested:0))
  return {page,pages,total:roster.length,players:roster.slice(page*PAGE_SIZE,(page+1)*PAGE_SIZE)}
}
function skinIdentity(player,edition){
  if(typeof player.texture==='string'&&/^[a-f0-9]{32,64}$/.test(player.texture))return {identifier:player.texture,kind:'applied-texture'}
  const uuid=cleanUuid(player.uuid),name=String(player.name||'')
  if(isFloodgate(uuid))return {identifier:dashed(uuid),kind:'floodgate'}
  // A leading dot is the common Floodgate username prefix. Never send it
  // to a Java username resolver or display an unrelated Java account's skin.
  if(name.startsWith('.')||edition==='bedrock'){
    const gamertag=(name.startsWith('.')?name.slice(1):name).replace(/_/g,' ')
    return BEDROCK_NAME.test(gamertag)?{gamertag,kind:'bedrock'}:null
  }
  // Mojang accounts use v4 UUIDs. Offline-mode v3 and zero UUIDs cannot
  // identify Mojang textures, so use the reported Java username instead.
  if(uuid&&uuid[12]==='4'&&/[89ab]/.test(uuid[16]))return {identifier:uuid,kind:'java'}
  if(JAVA_NAME.test(name))return {identifier:name,kind:'java-name'}
  return null
}
function avatarUrl(identifier){return 'https://mc-heads.net/avatar/'+encodeURIComponent(identifier)+'/64.png'}
function createSkinResolver({fetcher=globalThis.fetch,now=Date.now}={}){
  const cache=new Map(),pending=new Map()
  let active=0
  async function resolve(player,edition){
    const identity=skinIdentity(player,edition)
    if(!identity)return null
    if(identity.identifier)return avatarUrl(identity.identifier)
    const key=identity.gamertag.toLowerCase()
    const saved=cache.get(key)
    if(saved&&saved.expires>now())return saved.url
    if(pending.has(key))return pending.get(key)
    // Only resolve visible players. Cap in-flight external lookups across
    // all guilds; an overloaded or offline skin provider cannot block status.
    if(active>=8)return null
    active++
    const task=(async()=>{
      let url=null
      try{
        const response=await fetcher('https://api.geysermc.org/v2/xbox/xuid/'+encodeURIComponent(identity.gamertag),{signal:AbortSignal.timeout(3500),redirect:'error'})
        if(response.ok){
          const data=await response.json()
          const raw=data?.xuid
          if((typeof raw==='string'&&/^\d{1,20}$/.test(raw))||(typeof raw==='number'&&Number.isSafeInteger(raw)&&raw>0)){
            const xuid=BigInt(raw)
            if(xuid>0n&&xuid<=0xffffffffffffffffn)url=avatarUrl(dashed(xuid.toString(16).padStart(32,'0')))
          }
        }
      }catch{/* Missing lookup, timeout, or rate limit: keep the player name. */}
      cache.delete(key)
      cache.set(key,{url,expires:now()+(url?3600000:60000)})
      if(cache.size>2000)cache.delete(cache.keys().next().value)
      return url
    })().finally(()=>{active--;pending.delete(key)})
    pending.set(key,task)
    return task
  }
  return {resolve}
}
module.exports={PAGE_SIZE,MAX_PLAYERS,cleanUuid,isFloodgate,normalizePlayers,pageOf,skinIdentity,avatarUrl,createSkinResolver}
