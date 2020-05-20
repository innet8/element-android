/*
 * Copyright (c) 2020 New Vector Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package im.vector.riotx.features.widgets

import android.content.Context
import android.text.TextUtils
import im.vector.matrix.android.api.query.QueryStringValue
import im.vector.matrix.android.api.session.Session
import im.vector.matrix.android.api.session.events.model.Event
import im.vector.matrix.android.api.session.events.model.EventType
import im.vector.matrix.android.api.session.events.model.toModel
import im.vector.matrix.android.api.session.room.model.Membership
import im.vector.matrix.android.api.session.room.model.PowerLevelsContent
import im.vector.matrix.android.api.session.room.powerlevels.PowerLevelsHelper
import im.vector.matrix.android.api.session.widgets.WidgetPostAPIMediator
import im.vector.matrix.android.api.util.JsonDict
import im.vector.riotx.R
import im.vector.riotx.core.resources.StringProvider
import im.vector.riotx.features.navigation.Navigator
import timber.log.Timber
import java.util.HashMap

class WidgetPostAPIHandler(private val context: Context,
                           private val roomId: String,
                           private val navigator: Navigator,
                           private val stringProvider: StringProvider,
                           private val widgetPostAPIMediator: WidgetPostAPIMediator,
                           private val session: Session) : WidgetPostAPIMediator.Handler {

    private val room = session.getRoom(roomId)!!

    override fun handleWidgetRequest(eventData: JsonDict): Boolean {
        return when (eventData["action"] as String?) {
            "integration_manager_open" -> handleIntegrationManagerOpenAction(eventData).run { true }
            "bot_options"              -> getBotOptions(eventData).run { true }
            "can_send_event"           -> canSendEvent(eventData).run { true }
            //"close_scalar"             -> finish().run { true }
            "get_membership_count"     -> getMembershipCount(eventData).run { true }
            //"get_widgets"              -> getWidgets(eventData).run { true }
            //"invite"                   -> inviteUser(eventData).run { true }
            "join_rules_state"         -> getJoinRules(eventData).run { true }
            "membership_state"         -> getMembershipState(eventData).run { true }
            "set_bot_options"          -> setBotOptions(eventData).run { true }
            "set_bot_power"            -> setBotPower(eventData).run { true }
            "set_plumbing_state"       -> setPlumbingState(eventData).run { true }
            //"set_widget"               -> setWidget(eventData).run { true }
            else                       -> false
        }
    }

    private fun handleIntegrationManagerOpenAction(eventData: JsonDict) {
        var integType: String? = null
        var integId: String? = null
        val data = eventData["data"]
        data
                .takeIf { it is Map<*, *> }
                ?.let {
                    val dict = data as Map<*, *>

                    dict["integType"]
                            .takeIf { it is String }
                            ?.let { integType = it as String }

                    dict["integId"]
                            .takeIf { it is String }
                            ?.let { integId = it as String }

                    // Add "type_" as a prefix
                    integType?.let { integType = "type_$integType" }
                }
        navigator.openIntegrationManager(context, integId, integType)
    }

    /**
     * Retrieve the latest botOptions event
     *
     * @param eventData the modular data
     */
    private fun getBotOptions(eventData: JsonDict) {
        if (checkRoomId(eventData) || checkUserId(eventData)) {
            return
        }
        val userId = eventData["user_id"] as String
        Timber.d("Received request to get options for bot $userId in room $roomId requested")
        val stateEvents = room.getStateEvents(setOf(EventType.BOT_OPTIONS))
        var botOptionsEvent: Event? = null
        val stateKey = "_$userId"
        for (stateEvent in stateEvents) {
            if (TextUtils.equals(stateEvent.stateKey, stateKey)) {
                if (null == botOptionsEvent || stateEvent.ageLocalTs ?: 0 > botOptionsEvent.ageLocalTs ?: 0) {
                    botOptionsEvent = stateEvent
                }
            }
        }
        if (null != botOptionsEvent) {
            Timber.d("Received request to get options for bot $userId returns $botOptionsEvent")
            widgetPostAPIMediator.sendObjectResponse(Event::class.java, botOptionsEvent, eventData)
        } else {
            Timber.d("Received request to get options for bot $userId returns null")
            widgetPostAPIMediator.sendObjectResponse(Event::class.java, null, eventData)
        }
    }

    private fun canSendEvent(eventData: JsonDict) {
        if (checkRoomId(eventData)) {
            return
        }
        Timber.d("Received request canSendEvent in room $roomId")
        if (room.roomSummary()?.membership != Membership.JOIN) {
            widgetPostAPIMediator.sendError(stringProvider.getString(R.string.widget_integration_must_be_in_room), eventData)
            return
        }

        val eventType = eventData["event_type"] as String
        val isState = eventData["is_state"] as Boolean

        Timber.d("## canSendEvent() : eventType $eventType isState $isState")

        val powerLevelsEvent = room.getStateEvent(EventType.STATE_ROOM_POWER_LEVELS)
        val powerLevelsContent = powerLevelsEvent?.content?.toModel<PowerLevelsContent>()
        val canSend = if (powerLevelsContent == null) {
            false
        } else {
            PowerLevelsHelper(powerLevelsContent).isAllowedToSend(eventType, session.myUserId)
        }
        if (canSend) {
            Timber.d("## canSendEvent() returns true")
            widgetPostAPIMediator.sendBoolResponse(true, eventData)
        } else {
            Timber.d("## canSendEvent() returns widget_integration_no_permission_in_room")
            widgetPostAPIMediator.sendError(stringProvider.getString(R.string.widget_integration_no_permission_in_room), eventData)
        }
    }

    /**
     * Provides the membership state
     *
     * @param eventData the modular data
     */
    private fun getMembershipState(eventData: JsonDict) {
        if (checkRoomId(eventData) || checkUserId(eventData)) {
            return
        }
        val userId = eventData["user_id"] as String
        Timber.d("membership_state of $userId in room $roomId requested")
        val roomMemberStateEvent = room.getStateEvent(EventType.STATE_ROOM_MEMBER, stateKey = QueryStringValue.Equals(userId, QueryStringValue.Case.SENSITIVE))
    }

    /**
     * Request the latest joined room event
     *
     * @param eventData the modular data
     */
    private fun getJoinRules(eventData: JsonDict) {
        if (checkRoomId(eventData)) {
            return
        }
        Timber.d("Received request join rules  in room $roomId")
        val joinedEvents = room.getStateEvents(setOf(EventType.STATE_ROOM_JOIN_RULES))
        if (joinedEvents.isNotEmpty()) {
            widgetPostAPIMediator.sendObjectResponse(Event::class.java, joinedEvents.last(), eventData)
        } else {
            widgetPostAPIMediator.sendError(stringProvider.getString(R.string.widget_integration_failed_to_send_request), eventData)
        }
    }

    /**
     * Update the 'plumbing state"
     *
     * @param eventData the modular data
     */
    private fun setPlumbingState(eventData: JsonDict) {
        if (checkRoomId(eventData)) {
            return
        }
        val description = "Received request to set plumbing state to status " + eventData["status"] + " in room " + roomId + " requested"
        Timber.d(description)

        val status = eventData["status"] as String

        val params = HashMap<String, Any>()
        params["status"] = status
        room.sendStateEvent(
                eventType = EventType.PLUMBING,
                stateKey = null,
                body = params,
                callback = WidgetAPICallback(widgetPostAPIMediator, eventData, stringProvider)
        )
    }

    /**
     * Update the bot options
     *
     * @param eventData the modular data
     */
    private fun setBotOptions(eventData: JsonDict) {
        if (checkRoomId(eventData) || checkUserId(eventData)) {
            return
        }
        val userId = eventData["user_id"] as String
        val description = "Received request to set options for bot $userId in room $roomId"
        Timber.d(description)
        val content = eventData["content"] as JsonDict
        val stateKey = "_$userId"
        room.sendStateEvent(
                eventType = EventType.BOT_OPTIONS,
                stateKey = stateKey,
                body = content,
                callback = WidgetAPICallback(widgetPostAPIMediator, eventData, stringProvider))
    }

    /**
     * Update the bot power levels
     *
     * @param eventData the modular data
     */
    private fun setBotPower(eventData: JsonDict) {
        if (checkRoomId(eventData) || checkUserId(eventData)) {
            return
        }
        val userId = eventData["user_id"] as String
        val description = "Received request to set power level to " + eventData["level"] + " for bot " + userId + " in room " + roomId
        Timber.d(description)
        val level = eventData["level"] as Int
        if (level >= 0) {
            // TODO
            //room.updateUserPowerLevels(userId, level, WidgetApiCallback(eventData, description))
        } else {
            Timber.e("## setBotPower() : Power level must be positive integer.")
            widgetPostAPIMediator.sendError(stringProvider.getString(R.string.widget_integration_positive_power_level), eventData)
        }
    }

    /**
     * Provides the number of members in the rooms
     *
     * @param eventData the modular data
     */
    private fun getMembershipCount(eventData: JsonDict) {
        if (checkRoomId(eventData)) {
            return
        }
        val numberOfJoinedMembers = room.getNumberOfJoinedMembers()
        widgetPostAPIMediator.sendIntegerResponse(numberOfJoinedMembers, eventData)
    }

    /**
     * Check if roomId is present in the event and match
     * Send response and return true in case of error
     *
     * @return true in case of error
     */
    private fun checkRoomId(eventData: JsonDict): Boolean {
        val roomIdInEvent = eventData["room_id"] as String?
        // Check if param is present
        if (null == roomIdInEvent) {
            widgetPostAPIMediator.sendError(stringProvider.getString(R.string.widget_integration_missing_room_id), eventData)
            return true
        }

        if (!TextUtils.equals(roomIdInEvent, roomId)) {
            widgetPostAPIMediator.sendError(stringProvider.getString(R.string.widget_integration_room_not_visible), eventData)
            return true
        }

        // OK
        return false
    }

    /**
     * Check if userId is present in the event
     * Send response and return true in case of error
     *
     * @return true in case of error
     */
    private fun checkUserId(eventData: JsonDict): Boolean {
        val userIdInEvent = eventData["user_id"] as String?
        // Check if param is present
        if (null == userIdInEvent) {
            widgetPostAPIMediator.sendError(stringProvider.getString(R.string.widget_integration_missing_user_id), eventData)
            return true
        }
        // OK
        return false
    }
}


