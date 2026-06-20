@file:OptIn(ExperimentalRpcApi::class, InternalRpcApi::class)
package wtf.jobin

import kotlinx.rpc.internal.utils.ExperimentalRpcApi
import kotlinx.rpc.internal.utils.InternalRpcApi

/**
* Constructs a new message.
* ```
* val message = ClientGreeting {
*    name = ...
* }
* ```
*/
operator fun ClientGreeting.Companion.invoke(body: ClientGreeting.Builder.() -> Unit): ClientGreeting {
    val msg = ClientGreetingInternal().apply(body)
    msg.checkRequiredFields()
    return msg
}

/**
* Copies the original message, including unknown fields.
* ```
* val copy = original.copy {
*    name = ...
* }
* ```
*/
fun ClientGreeting.copy(body: ClientGreeting.Builder.() -> Unit = {}): ClientGreeting {
    return this.asInternal().copyInternal(body)
}

/**
* Constructs a new message.
* ```
* val message = ServerGreeting {
*    content = ...
* }
* ```
*/
operator fun ServerGreeting.Companion.invoke(body: ServerGreeting.Builder.() -> Unit): ServerGreeting {
    val msg = ServerGreetingInternal().apply(body)
    msg.checkRequiredFields()
    return msg
}

/**
* Copies the original message, including unknown fields.
* ```
* val copy = original.copy {
*    content = ...
* }
* ```
*/
fun ServerGreeting.copy(body: ServerGreeting.Builder.() -> Unit = {}): ServerGreeting {
    return this.asInternal().copyInternal(body)
}
