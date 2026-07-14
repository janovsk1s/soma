package com.soma.lanserver

import java.net.InetAddress
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivateLanAddressTest {
    @Test
    fun `accepts RFC 1918 IPv4 addresses`() {
        assertTrue(isPrivateLanAddress(InetAddress.getByName("10.0.0.7")))
        assertTrue(isPrivateLanAddress(InetAddress.getByName("172.16.4.1")))
        assertTrue(isPrivateLanAddress(InetAddress.getByName("192.168.1.20")))
    }

    @Test
    fun `accepts IPv6 unique-local and deprecated site-local addresses`() {
        assertTrue(isPrivateLanAddress(InetAddress.getByName("fd12:3456:789a::1")))
        assertTrue(isPrivateLanAddress(InetAddress.getByName("fc00::1")))
        assertTrue(isPrivateLanAddress(InetAddress.getByName("fec0::1")))
    }

    @Test
    fun `rejects public, loopback, link-local, and wildcard addresses`() {
        assertFalse(isPrivateLanAddress(InetAddress.getByName("203.0.113.1")))
        assertFalse(isPrivateLanAddress(InetAddress.getByName("2001:db8::1")))
        assertFalse(isPrivateLanAddress(InetAddress.getByName("127.0.0.1")))
        assertFalse(isPrivateLanAddress(InetAddress.getByName("::1")))
        assertFalse(isPrivateLanAddress(InetAddress.getByName("169.254.10.10")))
        assertFalse(isPrivateLanAddress(InetAddress.getByName("fe80::1")))
        assertFalse(isPrivateLanAddress(InetAddress.getByName("0.0.0.0")))
        assertFalse(isPrivateLanAddress(InetAddress.getByName("::")))
    }

    @Test
    fun `configuration accepts an IPv6 unique-local bind address`() {
        val config = LanServerConfig(InetAddress.getByName("fd00::a"))
        assertTrue(config.bindAddress is java.net.Inet6Address)
    }
}
