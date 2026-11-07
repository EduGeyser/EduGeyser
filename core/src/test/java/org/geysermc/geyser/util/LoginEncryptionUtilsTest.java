/*
 * Copyright (c) 2026 GeyserMC. http://geysermc.org
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 *
 * @author GeyserMC
 * @link https://github.com/GeyserMC/Geyser
 */

package org.geysermc.geyser.util;

import io.github.sendablemetatype.netty.util.nethernet.TransportIdentityBinding;
import io.netty.channel.Channel;
import io.netty.channel.embedded.EmbeddedChannel;
import org.geysermc.geyser.session.GeyserSession;
import org.junit.jupiter.api.Test;

import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.spec.ECGenParameterSpec;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LoginEncryptionUtilsTest {

    @Test
    void aLoginUnderTheProvenKeyGoesOn() throws Exception {
        PublicKey proven = p384Key();
        GeyserSession session = sessionOn(boundChannel(proven));

        assertTrue(LoginEncryptionUtils.transportAcceptsLoginKey(session, proven));
        verify(session, never()).disconnect(anyString());
    }

    @Test
    void aLoginUnderAnotherKeyIsRefused() throws Exception {
        GeyserSession session = sessionOn(boundChannel(p384Key()));

        assertFalse(LoginEncryptionUtils.transportAcceptsLoginKey(session, p384Key()));
        verify(session).disconnect(anyString());
    }

    @Test
    void aSecondLoginOnTheSameConnectionIsRefused() throws Exception {
        PublicKey proven = p384Key();
        GeyserSession session = sessionOn(boundChannel(proven));

        assertTrue(LoginEncryptionUtils.transportAcceptsLoginKey(session, proven));
        assertFalse(LoginEncryptionUtils.transportAcceptsLoginKey(session, proven));
        verify(session).disconnect(anyString());
    }

    @Test
    void aLoginOnAChannelWithoutABindingGoesOn() throws Exception {
        GeyserSession session = sessionOn(new EmbeddedChannel());

        assertTrue(LoginEncryptionUtils.transportAcceptsLoginKey(session, p384Key()));
        verify(session, never()).disconnect(anyString());
    }

    @Test
    void aForwardedLoginGoesOnWhateverKeyTheProxySignedItWith() throws Exception {
        // The offer proved the proxy's NetherNet key; the forwarded login is re-signed by the proxy
        GeyserSession session = sessionOn(boundChannel(p384Key()));

        assertTrue(LoginEncryptionUtils.transportAcceptsForwardedLogin(session));
        verify(session, never()).disconnect(anyString());
    }

    @Test
    void aForwardedLoginSpendsTheBindingSoNoSecondLoginFollows() throws Exception {
        PublicKey proven = p384Key();
        GeyserSession session = sessionOn(boundChannel(proven));

        assertTrue(LoginEncryptionUtils.transportAcceptsForwardedLogin(session));
        assertFalse(LoginEncryptionUtils.transportAcceptsForwardedLogin(session));
        assertFalse(LoginEncryptionUtils.transportAcceptsLoginKey(session, proven));
    }

    @Test
    void aForwardedLoginOnAChannelWithoutABindingGoesOn() {
        GeyserSession session = sessionOn(new EmbeddedChannel());

        assertTrue(LoginEncryptionUtils.transportAcceptsForwardedLogin(session));
        verify(session, never()).disconnect(anyString());
    }

    private static Channel boundChannel(PublicKey proven) {
        EmbeddedChannel channel = new EmbeddedChannel();
        TransportIdentityBinding.install(channel, TransportIdentityBinding.forKey(proven));
        return channel;
    }

    private static GeyserSession sessionOn(Channel channel) {
        GeyserSession session = mock(GeyserSession.class, RETURNS_DEEP_STUBS);
        when(session.getUpstream().getSession().getPeer().getChannel()).thenReturn(channel);
        return session;
    }

    private static PublicKey p384Key() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp384r1"));
        return generator.generateKeyPair().getPublic();
    }
}
