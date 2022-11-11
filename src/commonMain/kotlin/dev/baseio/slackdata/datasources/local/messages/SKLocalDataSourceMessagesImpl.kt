package dev.baseio.slackdata.datasources.local.messages

import database.SlackMessage
import dev.baseio.database.SlackDB
import dev.baseio.security.RsaEcdsaKeyManagerInstances
import dev.baseio.slackdata.datasources.local.channels.skUser
import dev.baseio.slackdomain.CoroutineDispatcherProvider
import dev.baseio.slackdata.local.asFlow
import dev.baseio.slackdata.local.mapToList
import dev.baseio.slackdata.mapper.EntityMapper
import dev.baseio.slackdomain.datasources.IDataDecryptor
import dev.baseio.slackdomain.datasources.IDataEncrypter
import dev.baseio.slackdomain.datasources.local.SKLocalKeyValueSource
import dev.baseio.slackdomain.datasources.local.channels.SKLocalDataSourceChannelMembers
import dev.baseio.slackdomain.model.message.DomainLayerMessages
import dev.baseio.slackdomain.datasources.local.messages.SKLocalDataSourceMessages
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext

class SKLocalDataSourceMessagesImpl constructor(
  private val slackMessageDao: SlackDB,
  private val entityMapper: EntityMapper<DomainLayerMessages.SKMessage, SlackMessage>,
  private val coroutineMainDispatcherProvider: CoroutineDispatcherProvider,
  private val iDataEncrypter: IDataEncrypter,
  private val iDataDecryptor: IDataDecryptor,
  private val skLocalKeyValueSource: SKLocalKeyValueSource,
  private val skLocalDataSourceChannelMembers: SKLocalDataSourceChannelMembers
) : SKLocalDataSourceMessages {

  override suspend fun getLocalMessages(workspaceId: String, userId: String): List<DomainLayerMessages.SKMessage> {
    return slackMessageDao.slackDBQueries.selectAllMessagesByChannelId(
      workspaceId,
      userId,
    ).executeAsList().run {
      this
        .map { slackMessage -> entityMapper.mapToDomain(slackMessage) }
    }
  }

  override fun streamLocalMessages(workspaceId: String, channelId: String): Flow<List<DomainLayerMessages.SKMessage>> {
    return slackMessageDao.slackDBQueries.selectAllMessagesByChannelId(
      workspaceId,
      channelId,
    )
      .asFlow()
      .flowOn(coroutineMainDispatcherProvider.io)
      .mapToList(coroutineMainDispatcherProvider.default)
      .map { slackMessages ->
        slackMessages
          .map { slackMessage -> entityMapper.mapToDomain(slackMessage) }
      }.map { skLastMessageList ->
        skLastMessageList.map { skLastMessage ->
          val myPrivateKey =
            RsaEcdsaKeyManagerInstances.getInstance(skLocalKeyValueSource.skUser().email!!).getPrivateKey().encoded

          val channelEncryptedPrivateKey = skLocalDataSourceChannelMembers.getChannelPrivateKeyForMe(
            workspaceId,
            channelId,
            skLocalKeyValueSource.skUser().uuid
          ).channelEncryptedPrivateKey.keyBytes

          val decryptedPrivateKeyBytes = iDataDecryptor.decrypt(channelEncryptedPrivateKey, myPrivateKey)
          finalMessageAfterDecryption(skLastMessage, decryptedPrivateKeyBytes)
        }
      }
      .flowOn(coroutineMainDispatcherProvider.default)
  }

  private fun finalMessageAfterDecryption(
    skLastMessage: DomainLayerMessages.SKMessage,
    privateKeyBytes: ByteArray
  ): DomainLayerMessages.SKMessage {
    var messageFinal = skLastMessage
    runCatching {
      messageFinal =
        messageFinal.copy(
          decodedMessage = iDataDecryptor.decrypt(messageFinal.message, privateKeyBytes = privateKeyBytes)
            .decodeToString()
        )
    }.exceptionOrNull()?.let {
      kotlin.runCatching {
        messageFinal =
          messageFinal.copy(
            decodedMessage = iDataDecryptor.decrypt(
              messageFinal.localMessage,
              privateKeyBytes = privateKeyBytes
            ).decodeToString()
          )
      }
    }
    return messageFinal
  }

  override suspend fun getLocalMessages(
    workspaceId: String,
    userId: String,
    limit: Int,
    offset: Int
  ): List<DomainLayerMessages.SKMessage> {
    return slackMessageDao.slackDBQueries.selectAllMessagesByChannelIdPaginated(
      workspaceId,
      userId,
      limit.toLong(),
      offset.toLong()
    ).executeAsList().run {
      this
        .map { slackMessage -> entityMapper.mapToDomain(slackMessage) }
    }
  }

  override fun streamLocalMessages(
    workspaceId: String,
    userId: String,
    limit: Int,
    offset: Int
  ): Flow<List<DomainLayerMessages.SKMessage>> {
    return slackMessageDao.slackDBQueries.selectAllMessagesByChannelIdPaginated(
      workspaceId,
      userId,
      limit.toLong(),
      offset.toLong()
    )
      .asFlow()
      .flowOn(coroutineMainDispatcherProvider.io)
      .mapToList(coroutineMainDispatcherProvider.default)
      .map { slackMessages ->
        slackMessages
          .map { slackMessage -> entityMapper.mapToDomain(slackMessage) }
      }
      .flowOn(coroutineMainDispatcherProvider.default)
  }

  override suspend fun saveMessage(
    params: DomainLayerMessages.SKMessage,
    message: ByteArray?
  ): DomainLayerMessages.SKMessage {
    val encryptedMessage = message?.let {
      val channelPublicKey =
        RsaEcdsaKeyManagerInstances.getInstance(skLocalKeyValueSource.skUser().email!!).getPublicKey().encoded
      iDataEncrypter.encrypt(
        message,
        channelPublicKey,
      )
    }
    return withContext(coroutineMainDispatcherProvider.io) {
      slackMessageDao.slackDBQueries.insertMessage(
        params.uuid,
        params.workspaceId,
        params.channelId,
        encryptedMessage ?: params.message,
        params.sender,
        params.createdDate,
        params.modifiedDate,
        if (params.isDeleted) 1 else 0,
        if (params.isSynced) 1 else 0,
        params.localMessage
      )
      params
    }
  }
}