@file:OptIn(ExperimentalRpcApi::class, InternalRpcApi::class)
package wtf.jobin

import kotlin.reflect.cast
import kotlinx.io.Buffer
import kotlinx.io.Source
import kotlinx.io.bytestring.isNotEmpty
import kotlinx.rpc.grpc.marshaller.GrpcMarshaller
import kotlinx.rpc.grpc.marshaller.GrpcMarshallerConfig
import kotlinx.rpc.internal.utils.ExperimentalRpcApi
import kotlinx.rpc.internal.utils.InternalRpcApi
import kotlinx.rpc.protobuf.ProtoConfig
import kotlinx.rpc.protobuf.internal.InternalMessage
import kotlinx.rpc.protobuf.internal.MsgFieldDelegate
import kotlinx.rpc.protobuf.internal.ProtoDescriptor
import kotlinx.rpc.protobuf.internal.ProtobufDecodingException
import kotlinx.rpc.protobuf.internal.WireDecoder
import kotlinx.rpc.protobuf.internal.WireEncoder
import kotlinx.rpc.protobuf.internal.WireSize
import kotlinx.rpc.protobuf.internal.WireType
import kotlinx.rpc.protobuf.internal.checkForPlatformDecodeException
import kotlinx.rpc.protobuf.internal.checkForPlatformEncodeException
import kotlinx.rpc.protobuf.internal.int32
import kotlinx.rpc.protobuf.internal.protoToString
import kotlinx.rpc.protobuf.internal.string
import kotlinx.rpc.protobuf.internal.tag

class ClientGreetingInternal: ClientGreeting.Builder, InternalMessage(fieldsWithPresence = 0) {
    @InternalRpcApi
    override val _size: Int by lazy { computeSize() }

    @InternalRpcApi
    override val _unknownFields: Buffer = Buffer()

    @InternalRpcApi
    internal var _unknownFieldsEncoder: WireEncoder? = null

    internal val __nameDelegate: MsgFieldDelegate<String> = MsgFieldDelegate { "" }
    override var name: String by __nameDelegate

    override fun hashCode(): Int {
        checkRequiredFields()
        var result = this.name.hashCode()
        return result
    }

    override fun equals(other: Any?): Boolean {
        checkRequiredFields()
        if (this === other) return true
        if (other == null || this::class != other::class) return false
        other as ClientGreetingInternal
        other.checkRequiredFields()
        if (this.name != other.name) return false
        return true
    }

    override fun toString(): String {
        return asString()
    }

    fun asString(indent: Int = 0): String {
        checkRequiredFields()
        val indentString = " ".repeat(indent)
        val nextIndentString = " ".repeat(indent + 4)
        val builder = StringBuilder()
        builder.appendLine("ClientGreeting(")
        builder.appendLine("${nextIndentString}name=${this.name},")
        builder.append("${indentString})")
        return builder.toString()
    }

    override fun copyInternal(): ClientGreetingInternal {
        return copyInternal { }
    }

    @InternalRpcApi
    fun copyInternal(body: ClientGreetingInternal.() -> Unit): ClientGreetingInternal {
        val copy = ClientGreetingInternal()
        copy.name = this.name
        copy.apply(body)
        this._unknownFields.copyTo(copy._unknownFields)
        return copy
    }

    @InternalRpcApi
    object MARSHALLER: GrpcMarshaller<ClientGreeting> {
        override fun encode(value: ClientGreeting, config: GrpcMarshallerConfig?): Source {
            val buffer = Buffer()
            val encoder = WireEncoder(buffer)
            val internalMsg = value.asInternal()
            checkForPlatformEncodeException {
                internalMsg.encodeWith(encoder, config as? ProtoConfig)
            }
            encoder.flush()
            return buffer
        }

        override fun decode(source: Source, config: GrpcMarshallerConfig?): ClientGreeting {
            WireDecoder(source).use {
                (config as? ProtoConfig)?.let { pbConfig -> it.recursionLimit = pbConfig.recursionLimit }
                val msg = ClientGreetingInternal()
                checkForPlatformDecodeException {
                    ClientGreetingInternal.decodeWith(msg, it, config as? ProtoConfig)
                }
                msg.checkRequiredFields()
                return msg
            }
        }
    }

    @InternalRpcApi
    object DESCRIPTOR: ProtoDescriptor<ClientGreeting> {
        override val fullName: String = "wtf.jobin.ClientGreeting"
    }

    @InternalRpcApi
    companion object {
        val DEFAULT: ClientGreeting by lazy { ClientGreetingInternal() }
    }
}

class ServerGreetingInternal: ServerGreeting.Builder, InternalMessage(fieldsWithPresence = 0) {
    @InternalRpcApi
    override val _size: Int by lazy { computeSize() }

    @InternalRpcApi
    override val _unknownFields: Buffer = Buffer()

    @InternalRpcApi
    internal var _unknownFieldsEncoder: WireEncoder? = null

    internal val __contentDelegate: MsgFieldDelegate<String> = MsgFieldDelegate { "" }
    override var content: String by __contentDelegate

    override fun hashCode(): Int {
        checkRequiredFields()
        var result = this.content.hashCode()
        return result
    }

    override fun equals(other: Any?): Boolean {
        checkRequiredFields()
        if (this === other) return true
        if (other == null || this::class != other::class) return false
        other as ServerGreetingInternal
        other.checkRequiredFields()
        if (this.content != other.content) return false
        return true
    }

    override fun toString(): String {
        return asString()
    }

    fun asString(indent: Int = 0): String {
        checkRequiredFields()
        val indentString = " ".repeat(indent)
        val nextIndentString = " ".repeat(indent + 4)
        val builder = StringBuilder()
        builder.appendLine("ServerGreeting(")
        builder.appendLine("${nextIndentString}content=${this.content},")
        builder.append("${indentString})")
        return builder.toString()
    }

    override fun copyInternal(): ServerGreetingInternal {
        return copyInternal { }
    }

    @InternalRpcApi
    fun copyInternal(body: ServerGreetingInternal.() -> Unit): ServerGreetingInternal {
        val copy = ServerGreetingInternal()
        copy.content = this.content
        copy.apply(body)
        this._unknownFields.copyTo(copy._unknownFields)
        return copy
    }

    @InternalRpcApi
    object MARSHALLER: GrpcMarshaller<ServerGreeting> {
        override fun encode(value: ServerGreeting, config: GrpcMarshallerConfig?): Source {
            val buffer = Buffer()
            val encoder = WireEncoder(buffer)
            val internalMsg = value.asInternal()
            checkForPlatformEncodeException {
                internalMsg.encodeWith(encoder, config as? ProtoConfig)
            }
            encoder.flush()
            return buffer
        }

        override fun decode(source: Source, config: GrpcMarshallerConfig?): ServerGreeting {
            WireDecoder(source).use {
                (config as? ProtoConfig)?.let { pbConfig -> it.recursionLimit = pbConfig.recursionLimit }
                val msg = ServerGreetingInternal()
                checkForPlatformDecodeException {
                    ServerGreetingInternal.decodeWith(msg, it, config as? ProtoConfig)
                }
                msg.checkRequiredFields()
                return msg
            }
        }
    }

    @InternalRpcApi
    object DESCRIPTOR: ProtoDescriptor<ServerGreeting> {
        override val fullName: String = "wtf.jobin.ServerGreeting"
    }

    @InternalRpcApi
    companion object {
        val DEFAULT: ServerGreeting by lazy { ServerGreetingInternal() }
    }
}

@InternalRpcApi
fun ClientGreetingInternal.checkRequiredFields() {
    // no required fields to check
}

@InternalRpcApi
fun ClientGreetingInternal.encodeWith(encoder: WireEncoder, config: ProtoConfig?) {
    if (this.name.isNotEmpty()) {
        encoder.writeString(fieldNr = 1, value = this.name)
    }

    _extensions.forEach { (key, value) ->
        value.descriptor.let { descriptor ->
            descriptor.encode(encoder, key, descriptor.valueType.cast(value.value), config)
        }
    }

    encoder.writeRawBytes(_unknownFields)
}

@InternalRpcApi
fun ClientGreetingInternal.Companion.decodeWith(msg: ClientGreetingInternal, decoder: WireDecoder, config: ProtoConfig?) {
    while (true) {
        val tag = decoder.readTag() ?: break // EOF, we read the whole message
        when {
            tag.fieldNr == 1 && tag.wireType == WireType.LENGTH_DELIMITED -> {
                msg.name = decoder.readString()
            }
            else -> {
                if (tag.wireType == WireType.END_GROUP) {
                    throw ProtobufDecodingException("Unexpected END_GROUP tag.")
                }

                if (config?.discardUnknownFields ?: false) {
                    decoder.skipUnknownField(tag)
                } else {
                    if (msg._unknownFieldsEncoder == null) {
                        msg._unknownFieldsEncoder = WireEncoder(msg._unknownFields)
                    }

                    decoder.readUnknownField(tag, msg._unknownFieldsEncoder!!)
                }
            }
        }
    }

    msg._unknownFieldsEncoder?.flush()
    msg._unknownFieldsEncoder = null
}

private fun ClientGreetingInternal.computeSize(): Int {
    var __result = 0
    if (this.name.isNotEmpty()) {
        __result += WireSize.string(this.name).let { WireSize.tag(1, WireType.LENGTH_DELIMITED) + WireSize.int32(it) + it }
    }

    __result += _unknownFields.size.toInt()
    return __result
}

@InternalRpcApi
fun ClientGreeting.asInternal(): ClientGreetingInternal {
    return this as? ClientGreetingInternal ?: error("Message ${this::class.simpleName} is a non-internal message type.")
}

@InternalRpcApi
fun ServerGreetingInternal.checkRequiredFields() {
    // no required fields to check
}

@InternalRpcApi
fun ServerGreetingInternal.encodeWith(encoder: WireEncoder, config: ProtoConfig?) {
    if (this.content.isNotEmpty()) {
        encoder.writeString(fieldNr = 2, value = this.content)
    }

    _extensions.forEach { (key, value) ->
        value.descriptor.let { descriptor ->
            descriptor.encode(encoder, key, descriptor.valueType.cast(value.value), config)
        }
    }

    encoder.writeRawBytes(_unknownFields)
}

@InternalRpcApi
fun ServerGreetingInternal.Companion.decodeWith(msg: ServerGreetingInternal, decoder: WireDecoder, config: ProtoConfig?) {
    while (true) {
        val tag = decoder.readTag() ?: break // EOF, we read the whole message
        when {
            tag.fieldNr == 2 && tag.wireType == WireType.LENGTH_DELIMITED -> {
                msg.content = decoder.readString()
            }
            else -> {
                if (tag.wireType == WireType.END_GROUP) {
                    throw ProtobufDecodingException("Unexpected END_GROUP tag.")
                }

                if (config?.discardUnknownFields ?: false) {
                    decoder.skipUnknownField(tag)
                } else {
                    if (msg._unknownFieldsEncoder == null) {
                        msg._unknownFieldsEncoder = WireEncoder(msg._unknownFields)
                    }

                    decoder.readUnknownField(tag, msg._unknownFieldsEncoder!!)
                }
            }
        }
    }

    msg._unknownFieldsEncoder?.flush()
    msg._unknownFieldsEncoder = null
}

private fun ServerGreetingInternal.computeSize(): Int {
    var __result = 0
    if (this.content.isNotEmpty()) {
        __result += WireSize.string(this.content).let { WireSize.tag(2, WireType.LENGTH_DELIMITED) + WireSize.int32(it) + it }
    }

    __result += _unknownFields.size.toInt()
    return __result
}

@InternalRpcApi
fun ServerGreeting.asInternal(): ServerGreetingInternal {
    return this as? ServerGreetingInternal ?: error("Message ${this::class.simpleName} is a non-internal message type.")
}
