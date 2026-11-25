'use strict'
const { randomBytes, timingSafeEqual } = require('node:crypto')
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/
const ID = /^\d{17,20}$/
function secretMatches(expected, header) {
  if (typeof header !== 'string' || !header.startsWith('Bearer ')) return false
  const a = Buffer.from(expected), b = Buffer.from(header.slice(7))
  return a.length === b.length && timingSafeEqual(a, b)
}
class Codes {
  constructor({ now = Date.now, limit = 4096 } = {}) { this.now = now; this.limit = limit; this.codes = new Map(); this.players = new Map() }
  clean() { for (const [code, v] of this.codes) if (v.expires <= this.now()) this.drop(code) }
  drop(code) { const v = this.codes.get(code); if (v) { this.codes.delete(code); this.players.delete(v.server + ':' + v.uuid) } }
  issue(server, guild, uuid) {
    if (!UUID.test(uuid)) throw new Error('Invalid identity')
    this.clean()
    const key = server + ':' + uuid, previous = this.players.get(key)
    if (previous) this.drop(previous)
    if (this.codes.size >= this.limit) throw new Error('Verification capacity reached')
    let code; do { code = randomBytes(6).toString('hex').toUpperCase() } while (this.codes.has(code))
    this.codes.set(code, { server, guild, uuid, expires: this.now() + 300_000 }); this.players.set(key, code); return code
  }
  consume(code, guild) {
    this.clean(); const v = this.codes.get(String(code).trim().toUpperCase())
    if (!v || v.guild !== guild) return null
    this.drop(String(code).trim().toUpperCase()); return v
  }
}
function planRoles(groups, mapping, verified, current, owned) {
  const wanted = new Set(groups.map(g => mapping[g]).filter(x => typeof x === 'string' && ID.test(x)))
  if (verified) wanted.add(verified)
  return { add: [...wanted].filter(x => !current.has(x)), remove: [...new Set(owned)].filter(x => !wanted.has(x) && current.has(x)), forget: [...new Set(owned)].filter(x => !wanted.has(x) && !current.has(x)) }
}
function validateConfig(cfg) {
  if (!Number.isInteger(cfg.port) || cfg.port < 1024 || cfg.port > 65535 || !Array.isArray(cfg.servers)) throw new Error('Invalid config')
  const servers = new Map(), guilds = new Set(), secrets = new Set()
  for (const s of cfg.servers) {
    if (!s || !/^[\w-]{1,64}$/.test(s.id) || (typeof s.guild !== 'string' || !ID.test(s.guild)) || !/^[a-fA-F0-9]{64,128}$/.test(s.secret) || servers.has(s.id) || guilds.has(s.guild) || secrets.has(s.secret)) throw new Error('Server IDs, guilds and secrets must be valid and unique')
    if (!s.mapping || typeof s.mapping !== 'object' || Array.isArray(s.mapping) || Object.entries(s.mapping).some(([k,v]) => !/^[\w.-]{1,100}$/.test(k) || (typeof v !== 'string' || !ID.test(v))) || (s.verifiedRole && (typeof s.verifiedRole !== 'string' || !ID.test(s.verifiedRole)))) throw new Error('Invalid role mapping')
    s.mapping = Object.assign(Object.create(null), s.mapping)
    servers.set(s.id, s); guilds.add(s.guild); secrets.add(s.secret)
  }
  return servers
}
module.exports = { UUID, ID, Codes, secretMatches, planRoles, validateConfig }
