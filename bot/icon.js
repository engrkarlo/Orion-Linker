'use strict'
const { createHash }=require('node:crypto')
const SIGNATURE=Buffer.from([137,80,78,71,13,10,26,10])
const MAX_BYTES=256*1024
// The Minecraft Java status protocol supplies a 64x64 PNG favicon.
function decodeIcon(value){
  if(typeof value!=='string'||value.length>Math.ceil(MAX_BYTES/3)*4+32)return null
  const encoded=value.replace(/^data:image\/png;base64,/i,'')
  if(!/^[A-Za-z0-9+/]+={0,2}$/.test(encoded)||encoded.length%4!==0)return null
  const bytes=Buffer.from(encoded,'base64')
  if(bytes.length<45||bytes.length>MAX_BYTES||!bytes.subarray(0,8).equals(SIGNATURE))return null
  if(bytes.readUInt32BE(8)!==13||bytes.toString('ascii',12,16)!=='IHDR'||bytes.readUInt32BE(16)!==64||bytes.readUInt32BE(20)!==64)return null
  // Validate the chunk envelope so truncated or arbitrary data is not uploaded.
  let pos=8,hasData=false
  while(pos+12<=bytes.length){
    const length=bytes.readUInt32BE(pos),type=bytes.toString('ascii',pos+4,pos+8),end=pos+12+length
    if(end>bytes.length)return null
    if(type==='IDAT'&&length>0)hasData=true
    if(type==='IEND')return length===0&&hasData&&end===bytes.length?bytes:null
    pos=end
  }
  return null
}
function iconFile(server,result){
  // An explicitly configured thumbnail always overrides automatic discovery.
  if(server.icon||server.type!=='java'||!Buffer.isBuffer(result?.icon))return null
  const bytes=result.icon
  const hash=createHash('sha256').update(bytes).digest('hex').slice(0,20)
  return {attachment:bytes,name:'mc-server-icon-'+hash+'.png'}
}
function prepareIconEdit(payload,message){
  // Only applies to the Minecraft status module's own icon attachments.
  // Keep matching files already uploaded; otherwise replace the old icon.
  const files=payload.files||[]
  const attachments=message?.attachments
  if(!attachments||typeof attachments.values!=='function')return {...payload,attachments:[]}
  const existing=[...attachments.values()]
  const retained=existing.filter(a=>!/^mc-server-icon-[a-f0-9]{20}\.png$/.test(a.name||''))
  const upload=[]
  for(const file of files){
    const match=existing.find(a=>a.name===file.name)
    if(match)retained.push(match)
    else upload.push(file)
  }
  return {...payload,files:upload,attachments:retained.map(a=>({id:a.id}))}
}
module.exports={decodeIcon,iconFile,prepareIconEdit}
