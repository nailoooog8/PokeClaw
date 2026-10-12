// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.agent.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class McpServerConfigTest {

    @Test
    fun `safeName converts symbols to underscore and lowercase`() {
        val config = McpServerConfig(
            name = "My-Server.Test@1",
            url = "https://example.com/mcp"
        )
        assertEquals("my_server_test_1", config.safeName())
    }

    @Test
    fun `normalizedHeaderValue auto-prepends Bearer for Authorization with bare token`() {
        val config = McpServerConfig(
            name = "server1",
            url = "https://example.com/mcp",
            headerName = "Authorization",
            headerValue = "sk-123456789"
        )
        assertEquals("Bearer sk-123456789", config.normalizedHeaderValue())
    }

    @Test
    fun `normalizedHeaderValue preserves existing Bearer or Basic scheme`() {
        val bearerConfig = McpServerConfig(
            name = "server1",
            url = "https://example.com/mcp",
            headerName = "Authorization",
            headerValue = "Bearer sk-already-bearer"
        )
        assertEquals("Bearer sk-already-bearer", bearerConfig.normalizedHeaderValue())

        val basicConfig = McpServerConfig(
            name = "server2",
            url = "https://example.com/mcp",
            headerName = "authorization",
            headerValue = "Basic dXNlcjpwYXNz"
        )
        assertEquals("Basic dXNlcjpwYXNz", basicConfig.normalizedHeaderValue())
    }

    @Test
    fun `normalizedHeaderValue preserves custom headers without modification`() {
        val config = McpServerConfig(
            name = "custom",
            url = "https://example.com/mcp",
            headerName = "X-API-Key",
            headerValue = "secret-key-raw"
        )
        assertEquals("secret-key-raw", config.normalizedHeaderValue())
    }

    @Test
    fun `normalizedHeaderValue returns null when headers are blank`() {
        val configNoAuth = McpServerConfig(
            name = "noauth",
            url = "https://example.com/mcp"
        )
        assertNull(configNoAuth.normalizedHeaderValue())

        val configEmptyVal = McpServerConfig(
            name = "empty",
            url = "https://example.com/mcp",
            headerName = "Authorization",
            headerValue = "   "
        )
        assertNull(configEmptyVal.normalizedHeaderValue())
    }

    @Test
    fun `detect correctly classifies auth types`() {
        assertEquals(McpAuthType.NONE, McpAuthType.detect(null, null))
        assertEquals(McpAuthType.NONE, McpAuthType.detect("", ""))
        assertEquals(McpAuthType.BEARER, McpAuthType.detect("Authorization", "Bearer sk-123"))
        assertEquals(McpAuthType.BEARER, McpAuthType.detect("Authorization", "sk-123"))
        assertEquals(McpAuthType.BASIC, McpAuthType.detect("Authorization", "Basic abc"))
        assertEquals(McpAuthType.CUSTOM, McpAuthType.detect("Authorization", "Digest abc"))
        assertEquals(McpAuthType.CUSTOM, McpAuthType.detect("Authorization", "Token abc"))
        assertEquals(McpAuthType.CUSTOM, McpAuthType.detect("X-Token", "xyz"))
    }

    @Test
    fun `displayCredential extracts raw token without scheme`() {
        val bearerConfig = McpServerConfig(
            name = "server1",
            url = "https://example.com/mcp",
            headerName = "Authorization",
            headerValue = "Bearer sk-12345"
        )
        assertEquals("sk-12345", bearerConfig.displayCredential())

        val basicConfig = McpServerConfig(
            name = "server2",
            url = "https://example.com/mcp",
            headerName = "Authorization",
            headerValue = "Basic mypass"
        )
        assertEquals("mypass", basicConfig.displayCredential())

        val customConfig = McpServerConfig(
            name = "server3",
            url = "https://example.com/mcp",
            headerName = "X-Auth",
            headerValue = "mytoken"
        )
        assertEquals("mytoken", customConfig.displayCredential())
    }

    @Test
    fun `parseList handles valid and fallback json formats`() {
        val jsonArray = """[{"name":"s1","url":"https://s1.com"},{"name":"s2","url":"https://s2.com"}]"""
        val list = McpServerConfig.parseList(jsonArray)
        assertEquals(2, list.size)
        assertEquals("s1", list[0].name)
        assertEquals("s2", list[1].name)

        val jsonSingle = """{"name":"sSingle","url":"https://single.com"}"""
        val singleList = McpServerConfig.parseList(jsonSingle)
        assertEquals(1, singleList.size)
        assertEquals("sSingle", singleList[0].name)
    }
}
