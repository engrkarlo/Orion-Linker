package io.github.engrkarlo.orionbridge;
/** Chat-only origin namespace. Never accepted by the moderation/verification parser. */
final class ChatIdentity {
    private ChatIdentity(){}
    static boolean inbound(String value){
        return SanctionState.validIdentity(value)||value!=null&&value.matches("discord-unlinked:[0-9]{17,20}");
    }
}
