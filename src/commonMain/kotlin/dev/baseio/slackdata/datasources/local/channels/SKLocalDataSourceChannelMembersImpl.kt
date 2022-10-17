package dev.baseio.slackdata.datasources.local.channels

import com.squareup.sqldelight.Query
import database.SlackChannelMember
import dev.baseio.database.SlackDB
import dev.baseio.slackdata.local.asFlow
import dev.baseio.slackdata.local.mapToList
import dev.baseio.slackdomain.CoroutineDispatcherProvider
import dev.baseio.slackdomain.datasources.local.channels.SKLocalDataSourceChannelMembers
import dev.baseio.slackdomain.model.channel.DomainLayerChannels
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class SKLocalDataSourceChannelMembersImpl(
  private val slackDB: SlackDB,
  private val coroutineDispatcherProvider: CoroutineDispatcherProvider
) : SKLocalDataSourceChannelMembers {

  override fun get(workspaceId: String, channelId: String): Flow<List<DomainLayerChannels.SkChannelMember>> {
    return slackDB.slackDBQueries.selectAllMembers(channelId, workspaceId).asFlow().mapToList().map {
      it.map { it.toChannelMember() }
    }
  }

  override suspend fun save(members: List<DomainLayerChannels.SkChannelMember>) {
    withContext(coroutineDispatcherProvider.io) {
      members.forEach { skChannelMember ->
        slackDB.slackDBQueries.insertMember(
          skChannelMember.uuid,
          skChannelMember.workspaceId,
          skChannelMember.channelId,
          skChannelMember.memberId
        )
      }
    }

  }
}

fun SlackChannelMember.toChannelMember() :DomainLayerChannels.SkChannelMember {
  return DomainLayerChannels.SkChannelMember(this.uuid,this.workspaceId,this.channelId,this.memberId)
}
