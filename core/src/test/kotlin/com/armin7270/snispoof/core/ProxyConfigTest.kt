package com.armin7270.snispoof.core

import com.armin7270.snispoof.core.proxy.ProxyConfigParser
import com.armin7270.snispoof.core.proxy.ProxyNetwork
import com.armin7270.snispoof.core.proxy.ProxyProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class ProxyConfigTest {

    /**
     * The store refuses vmess at import time, so the filter it relies on has to
     * keep working: a mixed link list must yield only the usable protocols.
     */
    @Test
    fun `vmess links are identifiable so import can refuse them`() {
        val vmessJson = """{"v":"2","ps":"x","add":"cf.example.com","port":"443",""" +
                """"id":"b831381d-6324-4d53-ad4f-8cda48b30811","aid":"0","net":"ws",""" +
                """"type":"none","host":"h.example.com","path":"/ws","tls":"tls"}"""
        val text = buildString {
            append("vless://d342d11e-d424-4583-b36e-524ab1f0afa4@104.16.0.0:443?type=ws&security=tls#A\n")
            append("vmess://").append(Base64.getEncoder().encodeToString(vmessJson.toByteArray())).append('\n')
            append("trojan://pw@104.17.0.0:443?security=tls#B\n")
        }
        val parsed = ProxyConfigParser.parseAll(text)
        assertEquals(3, parsed.size)
        assertEquals(1, parsed.count { it.proto == ProxyProtocol.VMESS })
        val usable = parsed.filter { it.proto != ProxyProtocol.VMESS }
        assertEquals(2, usable.size)
        assertTrue(usable.none { it.proto == ProxyProtocol.VMESS })
    }

    @Test
    fun `parses vless ws tls config`() {
        val uri = "vless://d342d11e-d424-4583-b36e-524ab1f0afa4@104.16.0.0:443" +
                "?type=ws&security=tls&path=%2Fmyws&host=example.com&sni=example.com&alpn=http%2F1.1#My%20Config"
        val c = ProxyConfigParser.parseOne(uri)
        assertNotNull(c)
        assertEquals(ProxyProtocol.VLESS, c!!.proto)
        assertEquals("104.16.0.0", c.address)
        assertEquals(443, c.port)
        assertEquals(ProxyNetwork.WS, c.net)
        assertTrue(c.tls)
        assertEquals("/myws", c.path)
        assertEquals("example.com", c.sni)
        assertEquals("example.com", c.host)
        assertEquals("My Config", c.name)
        assertEquals("http/1.1", c.alpn)
    }

    @Test
    fun `parses trojan raw tcp config`() {
        val uri = "trojan://pass123@5.6.7.8:443?security=tls&type=tcp#trojan-node"
        val c = ProxyConfigParser.parseOne(uri)
        assertNotNull(c)
        assertEquals(ProxyProtocol.TROJAN, c!!.proto)
        assertEquals("pass123", c.password)
        assertEquals(5, c.address.split(".")[0].toInt())
        assertEquals(443, c.port)
        assertTrue(c.tls)
        assertEquals(ProxyNetwork.TCP, c.net)
    }

    @Test
    fun `parses vmess base64 json`() {
        val json = """{"v":"2","ps":"test-vmess","add":"cf.example.com","port":"443","id":"b831381d-6324-4d53-ad4f-8cda48b30811","aid":"0","net":"ws","type":"none","host":"real.example.com","path":"/ws","tls":"tls"}"""
        val uri = "vmess://" + Base64.getEncoder().encodeToString(json.toByteArray())
        val c = ProxyConfigParser.parseOne(uri)
        assertNotNull(c)
        assertEquals(ProxyProtocol.VMESS, c!!.proto)
        assertEquals("cf.example.com", c.address)
        assertEquals(443, c.port)
        assertEquals("b831381d-6324-4d53-ad4f-8cda48b30811", c.uuid)
        assertEquals(ProxyNetwork.WS, c.net)
        assertTrue(c.tls)
        assertEquals("real.example.com", c.host)
        assertEquals("/ws", c.path)
    }

    @Test
    fun `parses multiple configs from mixed text`() {
        val text = """
            some junk before
            vless://11111111-2222-3333-4444-555555555555@1.2.3.4:443?type=ws&security=tls&path=/a#node1
            trojan://pw@5.6.7.8:443#node2
            more junk
        """.trimIndent()
        val all = ProxyConfigParser.parseAll(text)
        assertEquals(2, all.size)
        assertEquals("node1", all[0].name)
        assertEquals("node2", all[1].name)
    }

    @Test
    fun `config roundtrip through json store codec`() {
        val uri = "vless://d342d11e-d424-4583-b36e-524ab1f0afa4@104.16.0.0:443?type=ws&security=tls&path=/x#n"
        val c = ProxyConfigParser.parseOne(uri)!!
        val encoded = com.armin7270.snispoof.core.proxy.ProxyConfig.encode(listOf(c))
        val decoded = com.armin7270.snispoof.core.proxy.ProxyConfig.decode(encoded)
        assertEquals(1, decoded.size)
        assertEquals(c.address, decoded[0].address)
        assertEquals(c.path, decoded[0].path)
        assertEquals(c.uuid, decoded[0].uuid)
    }
}
