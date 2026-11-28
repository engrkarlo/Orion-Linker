'use strict'
const V2=32768,PRIVATE=64
const esc=v=>String(v??'Unavailable').replace(/[\\*_~|>#`]/g,'\\$&').replace(/@/g,'@\u200b').slice(0,1300)
const code=v=>'`'+String(v??'Unavailable').replace(/[`\r\n\u0000-\u001f]/g,' ').slice(0,200)+'`'
const text=content=>({type:10,content:String(content).slice(0,4000)})
const row=(...components)=>({type:1,components})
const btn=(action,label,style=2,disabled=false)=>({type:2,custom_id:'ob2:'+action,label,style,disabled})
const select=(action,placeholder,options)=>row({type:3,custom_id:'ob2:'+action,placeholder,options:options.slice(0,25)})
const card=(components,{private:priv=true,color=0x8b5cf6}={})=>({flags:V2|(priv?PRIVATE:0),allowedMentions:{parse:[],repliedUser:false},components:[{type:17,accent_color:color,components}]})
const notice=(title,body,error=false)=>card([text('## '+esc(title)),text(esc(body))],{color:error?0xed4245:0x8b5cf6})
const modal=(id,title,...inputs)=>({custom_id:'ob2:'+id,title,components:inputs.map(input=>row(input))})
const input=(id,label,value='',max=500,style=1)=>({type:4,custom_id:id,label,style,value:String(value),required:true,max_length:max})
function servers(list){return card([text('## OrionBridge Control Center\nSelect a paired Minecraft server.'),...(list.length?[select('server','Choose server',list.map(s=>({label:String(s.name).slice(0,100),value:s.id})))]:[text('No Pull-paired Java servers. Configure one through /mcserver manage first.')])])}
function manager(server,c,health){
 const id=server.id
 return card([text('## OrionBridge · '+esc(server.name)+'\n-# Identity & security · Pull transport'),
  text('**Audit:** '+(c?.audit?'Enabled':'Off')+'\n**Verification:** '+(c?.verification?'Enabled':'Off')+'\n**Moderation:** '+(c?.moderation?'Enabled':'Off')+'\n**Health:** '+esc(health||'Not checked')),
  text('**Verification channel:** '+(c?.verifyChannel?'<#'+c.verifyChannel+'>':'Not set')+'\n**Security channel:** '+(c?.securityChannel?'<#'+c.securityChannel+'>':'Not set')+'\n**Punishment channel:** '+(c?.punishmentChannel?'<#'+c.punishmentChannel+'>':'Not set')),
  row(btn('setup:'+id,c?'Setup credentials':'Create setup',1),btn('channels:'+id,'Channels',2,!c),btn('staff:'+id,'Staff roles',2,!c)),
  row(btn('toggle-audit:'+id,c?.audit?'Disable audit':'Enable audit',2,!c),btn('toggle-verification:'+id,c?.verification?'Disable verification':'Enable verification',2,!c),btn('toggle-moderation:'+id,c?.moderation?'Disable moderation':'Enable moderation',2,!c)),
  row(btn('publish:'+id,'Publish verification',1,!c?.verifyChannel),btn('events:'+id,'Evidence',2,!c),btn('cases:'+id,'Cases',2,!c),btn('compare:'+id,'Compare accounts',2,!c)),
  row(btn('overview','All servers'))])
}
function channels(s,c){
 return card([text('## Channel Manager · '+esc(s.name)+'\nSelect a channel for each destination. Security and punishment channels must be private.'),...['verifyChannel','securityChannel','punishmentChannel'].flatMap((key,i)=>[text('**'+['Verification','Security alerts','Punishment logs'][i]+'** · '+(c[key]?'<#'+c[key]+'>':'Not set')),row({type:8,custom_id:'ob2:channel-'+key+':'+s.id,placeholder:'Choose text channel',channel_types:[0],min_values:1,max_values:1})]),row(btn('manage:'+s.id,'Back'))])
}
function staff(s,c){return card([text('## Security staff roles\nSelected roles can inspect evidence and create punishment requests. Manage Server is required to approve or revoke cases. Existing role permissions are not changed.'),row({type:6,custom_id:'ob2:staff-save:'+s.id,placeholder:'Select staff roles',min_values:0,max_values:10,default_values:c.staffRoles.map(id=>({id,type:'role'}))}),row(btn('manage:'+s.id,'Back'))])}
function verifyPanel(s){return card([text('## Account Verification\n-# '+esc(s.name)+' · OrionBridge'),text('Link your Minecraft identity to Discord before playing.\n**1.** Join Minecraft and open the account dialog.\n**2.** Generate a private verification code.\n**3.** Submit it below and confirm your Discord identity in Minecraft.'),text('-# One account per identity. We never ask for your password.'),row(btn('link:'+s.id,'Link Account',1),btn('account:'+s.id,'My Account'),btn('help:'+s.id,'Help'))],{private:false})}
function evidence(s,e,priv=false){
 const detail=JSON.parse(e.body)
 return card([text('## '+(e.kind==='OP_STATUS_CHANGED'?'Operator status changed':e.kind==='SENSITIVE_COMMAND_ATTEMPT'?'Sensitive command attempt':e.kind==='BLOCK_ACTIVITY_THRESHOLD'?'Unusual block activity':'Security evidence')+'\n-# '+esc(s.name)+' · '+code(e.id)),
 text('**Identity:** '+code(e.identity||'Unresolved')+'\n**Discord at event:** '+code(e.discord||'Unknown / not linked')+'\n**Observed:** <t:'+Math.floor(e.at/1000)+':F>'),
 text('**Evidence**\n'+esc(detail.detail)+'\n-# Source: '+esc(detail.source)+' · Observation, not a finding of guilt.'),
 row(btn('evidence:'+s.id+':'+e.id,'View evidence'),btn('export:'+s.id+':'+e.id,'Export JSON'),btn('punish:'+s.id+':'+e.id,'Punish player',4,!e.identity))],{private:priv,color:0xf59e0b})
}
function evidenceList(s,events){return card([text('## Evidence · '+esc(s.name)),...(events.length?[select('event-select:'+s.id,'Open event',events.map(e=>({label:e.kind.slice(0,90),description:new Date(e.at).toISOString(),value:e.id})))]:[text('No evidence recorded.')]),row(btn('manage:'+s.id,'Back'))])}
function caseCard(s,c,priv=true){
 const until=c.expires===null?'Indefinite':'<t:'+Math.floor(c.expires/1000)+':R>'
 const approve=c.state==='PENDING_APPROVAL',revokable=['PENDING_APPROVAL','PENDING_ENFORCEMENT','PARTIAL','ACTIVE'].includes(c.state)
 return card([text('## Moderation case\n-# '+esc(s.name)+' · '+code(c.id)),text('**Identity:** '+code(c.identity)+'\n**Discord:** '+code(c.discord||'Not linked')+'\n**Type:** '+code(c.type)+'\n**Scope:** '+code(c.scope)+'\n**Expires:** '+until+'\n**State:** '+code(c.state)),text('**Reason**\n'+esc(c.reason)+'\n-# Requested by '+code(c.actor)+' · ACTIVE means persisted by target bridge, not independently verified event coverage.'),row(btn('approve:'+s.id+':'+c.id,'Approve',3,!approve),btn('revoke:'+s.id+':'+c.id,'Revoke',4,!revokable))],{private:priv,color:0x8b5cf6})
}
function caseList(s,cases){return card([text('## Punishment history · '+esc(s.name)),...(cases.length?[select('case-select:'+s.id,'Open case',cases.map(c=>({label:(c.type+' · '+c.state).slice(0,100),description:c.identity.slice(0,100),value:c.id})))]:[text('No cases recorded.')]),row(btn('manage:'+s.id,'Back'))])}
const types=['MUTE','FREEZE','BUILD_RESTRICTION','QUARANTINE','TEMP_BAN','PERMANENT_BAN']
function punish(s,e){return card([text('## Issue Punishment\n**Target:** '+code(e.identity)+'\n-# Evidence '+code(e.id)+' · Second staff approval required.'),select('punish-type:'+s.id+':'+e.id,'Select punishment type',types.map(type=>({label:type.replaceAll('_',' ').toLowerCase(),value:type})))])}
module.exports={V2,PRIVATE,esc,code,text,row,btn,select,card,notice,modal,input,servers,manager,channels,staff,verifyPanel,evidence,evidenceList,caseCard,caseList,punish,types}
