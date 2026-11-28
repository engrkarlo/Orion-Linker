'use strict'
const {SlashCommandBuilder}=require('discord.js')
const {createHostRuntime}=require('./bot/host-runtime')
let runtime
module.exports={
 commands:[{data:new SlashCommandBuilder().setName('orionbridge').setDescription('Minecraft identity and security').addSubcommand(s=>s.setName('manage').setDescription('Manage paired servers')).addSubcommand(s=>s.setName('account').setDescription('View your linked identity')),run:async({interaction})=>runtime?runtime.command(interaction):interaction.reply({content:'OrionBridge is not ready.',flags:64})}],
 async onReady({client,manager}){
  const registry=require('../../src/minecraft-status/manager').store()
  runtime=createHostRuntime({client,manager,registry});runtime.start()
  manager.log?.('OrionBridge identity and security extension loaded.')
 },
 async onUnload(){const r=runtime;runtime=null;await r?.stop()},
 async onInteraction({interaction}){return runtime?runtime.interaction(interaction):false}
}
