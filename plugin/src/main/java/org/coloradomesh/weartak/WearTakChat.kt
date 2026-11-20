package org.coloradomesh.weartak

import org.coloradomesh.meshcore.tak.ChatSpec
import org.coloradomesh.meshcore.tak.escapeXml
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * GeoChat for WearTAK, shaped so WearTAK files each message as a one-to-one conversation with
 * its sender: a mesh channel (sender `#Public`) or a mesh node.
 *
 * WearTAK (`CotMessageParser.parseChatRooms`) treats a chat as a group only when its id is
 * neither the sender's nor the user's uid, and then accepts it only if the user's uid is among
 * the participants. A plugin isn't told the user's uid, so a group couldn't be addressed. A chat
 * whose id is the sender's uid is a one-to-one conversation instead, and WearTAK accepts it when
 * `remarks to` is the user's callsign, which WearTAK does tell plugins. The conversation is keyed
 * on the sender's uid and titled with its callsign, which is also what a reply is addressed to
 * (M4).
 *
 * [ChatSpec] comes from MeshPicture, as for the bridge; its room fields (the ATAK user's) are not
 * used here.
 */
object WearTakChat {
    /** As in CotEvents: chat stays in the history; stale only bounds how long it's relayed. */
    private val STALE: Duration = Duration.ofHours(24)

    fun direct(spec: ChatSpec, toCallsign: String, now: Instant): String {
        val t = time(now)
        val sent = time(if (spec.sentAt.isAfter(now)) now else spec.sentAt)
        val sender = escapeXml(spec.senderUid)
        val callsign = escapeXml(spec.senderCallsign)
        val to = escapeXml(toCallsign)
        val id = escapeXml(spec.messageId)
        val uid = escapeXml("GeoChat.${spec.senderUid}.${spec.messageId}")
        return "<event version=\"2.0\" uid=\"$uid\" type=\"b-t-f\"" +
            " time=\"$t\" start=\"$t\" stale=\"${time(now.plus(STALE))}\" how=\"h-g-i-g-o\">" +
            "<point lat=\"0\" lon=\"0\" hae=\"9999999.0\" ce=\"9999999.0\" le=\"9999999.0\"/>" +
            "<detail>" +
            "<__chat parent=\"RootContactGroup\" groupOwner=\"false\" messageId=\"$id\"" +
            " chatroom=\"$callsign\" id=\"$sender\" senderCallsign=\"$callsign\">" +
            "<chatgrp uid0=\"$sender\" uid1=\"$sender\" id=\"$sender\"/>" +
            "</__chat>" +
            "<link uid=\"$sender\" type=\"a-f-G-U-C\" relation=\"p-p\"/>" +
            "<remarks source=\"BAO.F.ATAK.$sender\" to=\"$to\" time=\"$sent\">" +
            escapeXml(spec.text) +
            "</remarks>" +
            "</detail>" +
            "</event>"
    }

    private fun time(at: Instant): String = at.truncatedTo(ChronoUnit.MILLIS).toString()
}
