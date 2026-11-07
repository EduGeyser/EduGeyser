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

import io.github.sendablemetatype.netty.channel.nethernet.nxs.admission.AdmissionPrincipal;
import io.github.sendablemetatype.netty.channel.nethernet.nxs.admission.TicketId;
import io.github.sendablemetatype.netty.util.nethernet.TransportIdentityBinding;
import io.netty.channel.Channel;
import io.netty.channel.embedded.EmbeddedChannel;
import org.cloudburstmc.protocol.bedrock.codec.v1002.Bedrock_v1002;
import org.cloudburstmc.protocol.bedrock.data.auth.CertificateChainPayload;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.LoginPacket;
import org.cloudburstmc.protocol.bedrock.packet.ServerToClientHandshakePacket;
import org.cloudburstmc.protocol.bedrock.util.EducationTokenValidationResult;
import org.cloudburstmc.protocol.bedrock.util.EncryptionUtils;
import org.geysermc.geyser.session.GeyserSession;
import org.geysermc.geyser.session.auth.AuthData;
import org.jose4j.json.JsonUtil;
import org.jose4j.jws.AlgorithmIdentifiers;
import org.jose4j.jws.JsonWebSignature;
import org.jose4j.jwx.HeaderParameterNames;
import org.jose4j.lang.JoseException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.net.InetSocketAddress;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// Tests that log in load EncryptionUtils, which fetches Minecraft's discovery data when it loads
class LoginEncryptionUtilsTest {

    @Test
    void anEducationLoginGetsTheHandshakeWithItsServerTokenAndNothingElse() throws Exception {
        KeyPair client = p384KeyPair();
        GeyserSession session = sessionOn(boundChannel(client.getPublic()));
        when(session.getUpstream().getSession().getCodec()).thenReturn(Bedrock_v1002.EDUCATION_CODEC);
        String serverToken = UUID.randomUUID() + "|" + UUID.randomUUID() + "|2099-01-01T00:00:00Z|00";

        EducationTokenValidationResult valid = mock(EducationTokenValidationResult.class);
        when(valid.getStatus()).thenReturn(EducationTokenValidationResult.Status.VALID);
        try (MockedStatic<EncryptionUtils> encryption = mockStatic(EncryptionUtils.class, CALLS_REAL_METHODS)) {
            // Only MESS can sign a server token
            encryption.when(() -> EncryptionUtils.validateEducationToken(serverToken)).thenReturn(valid);

            LoginEncryptionUtils.validateLogin(session, login(client, Map.of(
                    "GameVersion", "1.21.132",
                    "IsEduMode", true,
                    "EduTokenChain", sign(client, Map.of("chain", serverToken)),
                    "ThirdPartyName", "Player")));
        }

        ArgumentCaptor<BedrockPacket> sent = ArgumentCaptor.forClass(BedrockPacket.class);
        verify(session).sendUpstreamPacketImmediately(sent.capture());
        Map<String, Object> claims = verifiedClaims(((ServerToClientHandshakePacket) sent.getValue()).getJwt());
        assertEquals(serverToken, claims.get("signedToken"));
        assertEquals(16, Base64.getDecoder().decode((String) claims.get("salt")).length);

        verify(session).setAuthData(any());
        verify(session, never()).disconnect(anyString());
        verify(session.getUpstream().getSession(), never()).enableEncryption(any());
    }

    @Test
    void aRetailLoginGetsNoHandshake() throws Exception {
        KeyPair client = p384KeyPair();
        GeyserSession session = sessionOn(boundChannel(client.getPublic()));

        // Self-signed, as with Xbox validation off; only IsEduMode tells it from an Education login
        LoginEncryptionUtils.validateLogin(session, login(client, Map.of("GameVersion", "1.26.62")));

        verify(session).setAuthData(any());
        verify(session, never()).sendUpstreamPacketImmediately(any());
        verify(session, never()).disconnect(anyString());
    }

    @Test
    void aLoginUnderTheProvenKeyGoesOn() throws Exception {
        PublicKey proven = p384KeyPair().getPublic();
        GeyserSession session = sessionOn(boundChannel(proven));

        assertTrue(LoginEncryptionUtils.transportAcceptsLoginKey(session, proven));
        verify(session, never()).disconnect(anyString());
    }

    @Test
    void aLoginUnderAnotherKeyIsRefused() throws Exception {
        GeyserSession session = sessionOn(boundChannel(p384KeyPair().getPublic()));

        assertFalse(LoginEncryptionUtils.transportAcceptsLoginKey(session, p384KeyPair().getPublic()));
        verify(session).disconnect(anyString());
    }

    @Test
    void aSecondLoginOnTheSameConnectionIsRefused() throws Exception {
        PublicKey proven = p384KeyPair().getPublic();
        GeyserSession session = sessionOn(boundChannel(proven));

        assertTrue(LoginEncryptionUtils.transportAcceptsLoginKey(session, proven));
        assertFalse(LoginEncryptionUtils.transportAcceptsLoginKey(session, proven));
        verify(session).disconnect(anyString());
    }

    @Test
    void aLoginOnNetherNetWithoutAProvenKeyGoesOn() throws Exception {
        EmbeddedChannel channel = new EmbeddedChannel();
        TransportIdentityBinding.install(channel, TransportIdentityBinding.unbound());
        GeyserSession session = sessionOn(channel);

        assertTrue(LoginEncryptionUtils.transportAcceptsLoginKey(session, p384KeyPair().getPublic()));
        verify(session, never()).disconnect(anyString());
    }

    @Test
    void aLoginOnAConnectionThatIsNotNetherNetIsRefused() throws Exception {
        GeyserSession session = sessionOn(new EmbeddedChannel());

        assertFalse(LoginEncryptionUtils.transportAcceptsLoginKey(session, p384KeyPair().getPublic()));
        verify(session).disconnect(anyString());
    }

    @Test
    void aForwardedLoginGoesOnWhateverKeyTheProxySignedItWith() throws Exception {
        // The offer proved the proxy's NetherNet key; the forwarded login is re-signed by the proxy
        GeyserSession session = sessionOn(boundChannel(p384KeyPair().getPublic()));

        assertTrue(LoginEncryptionUtils.transportAcceptsForwardedLogin(session));
        verify(session, never()).disconnect(anyString());
    }

    @Test
    void aForwardedLoginSpendsTheBindingSoNoSecondLoginFollows() throws Exception {
        PublicKey proven = p384KeyPair().getPublic();
        GeyserSession session = sessionOn(boundChannel(proven));

        assertTrue(LoginEncryptionUtils.transportAcceptsForwardedLogin(session));
        assertFalse(LoginEncryptionUtils.transportAcceptsForwardedLogin(session));
        assertFalse(LoginEncryptionUtils.transportAcceptsLoginKey(session, proven));
    }

    @Test
    void aForwardedLoginOnAConnectionThatIsNotNetherNetIsRefused() {
        GeyserSession session = sessionOn(new EmbeddedChannel());

        assertFalse(LoginEncryptionUtils.transportAcceptsForwardedLogin(session));
        verify(session).disconnect(anyString());
    }

    @Test
    void anNxsJoinBehindWaterdogPEMustStillBeSignedWithTheAdmittedKey() throws Exception {
        // The service admitted key A; a login under key B with forwarded fields would claim anyone
        KeyPair admitted = p384KeyPair();
        Channel channel = nxsChannel(admitted.getPublic());
        GeyserSession session = forwardingSessionOn(channel);

        LoginEncryptionUtils.validateLogin(session, login(p384KeyPair(), withForwardedFields()));

        verify(session).disconnect(anyString());
        verify(session, never()).setAuthData(any());
        assertEquals(TransportIdentityBinding.State.REJECTED, TransportIdentityBinding.state(channel));
    }

    @Test
    void anNxsJoinBehindWaterdogPEIsDirectAndItsForwardedFieldsAreIgnored() throws Exception {
        KeyPair admitted = p384KeyPair();
        Channel channel = nxsChannel(admitted.getPublic());
        GeyserSession session = forwardingSessionOn(channel);

        LoginEncryptionUtils.validateLogin(session, login(admitted, withForwardedFields()));

        ArgumentCaptor<AuthData> auth = ArgumentCaptor.forClass(AuthData.class);
        verify(session).setAuthData(auth.capture());
        assertEquals("", auth.getValue().xuid(), "the chain's XUID, not the forwarded one");
        verify(session.getUpstream(), never()).setInetAddress(any());
        verify(session, never()).disconnect(anyString());
        assertEquals(TransportIdentityBinding.State.ACCEPTED, TransportIdentityBinding.state(channel));
    }

    @Test
    void aLoginWaterdogPEForwardsOverItsOwnConnectionTakesTheForwardedFields() throws Exception {
        // The ruled WaterdogPE path, unchanged: the proxy's connection proved the proxy's key
        Channel channel = boundChannel(p384KeyPair().getPublic());
        GeyserSession session = forwardingSessionOn(channel);

        LoginEncryptionUtils.validateLogin(session, login(p384KeyPair(), withForwardedFields()));

        ArgumentCaptor<AuthData> auth = ArgumentCaptor.forClass(AuthData.class);
        verify(session).setAuthData(auth.capture());
        assertEquals(FORWARDED_XUID, auth.getValue().xuid());
        verify(session.getUpstream()).setInetAddress(new InetSocketAddress(FORWARDED_IP, 0));
        assertEquals(TransportIdentityBinding.State.ACCEPTED, TransportIdentityBinding.state(channel));
    }

    private static final String FORWARDED_IP = "203.0.113.9";
    private static final String FORWARDED_XUID = "2535412345678901";

    private static Map<String, ?> withForwardedFields() {
        return Map.of("GameVersion", "1.26.62", "Waterdog_IP", FORWARDED_IP, "Waterdog_XUID", FORWARDED_XUID);
    }

    /** A connection NXS admitted for the key, marked as NetworkM marks it before the endpoint exposes it. */
    private static Channel nxsChannel(PublicKey admitted) {
        Channel channel = boundChannel(admitted);
        channel.attr(AdmissionPrincipal.KEY).set(
                new AdmissionPrincipal(new TicketId("0123456789abcdef0123456789abcdef"), "12345", "K001"));
        return channel;
    }

    private static GeyserSession forwardingSessionOn(Channel channel) {
        GeyserSession session = sessionOn(channel);
        when(session.getGeyser().config().advanced().bedrock().useWaterdogpeForwarding()).thenReturn(true);
        return session;
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

    /**
     * A login with a one-entry, self-signed chain, as Education clients send.
     */
    private static LoginPacket login(KeyPair client, Map<String, ?> clientData) throws JoseException {
        String chain = sign(client, Map.of(
                "identityPublicKey", Base64.getEncoder().encodeToString(client.getPublic().getEncoded()),
                "extraData", Map.of("displayName", "", "identity", UUID.randomUUID().toString(), "XUID", "")));
        LoginPacket packet = new LoginPacket();
        packet.setAuthPayload(new CertificateChainPayload(List.of(chain)));
        packet.setClientJwt(sign(client, clientData));
        return packet;
    }

    private static String sign(KeyPair key, Map<String, ?> claims) throws JoseException {
        JsonWebSignature jws = new JsonWebSignature();
        jws.setAlgorithmHeaderValue(AlgorithmIdentifiers.ECDSA_USING_P384_CURVE_AND_SHA384);
        jws.setHeader(HeaderParameterNames.X509_URL, Base64.getEncoder().encodeToString(key.getPublic().getEncoded()));
        jws.setKey(key.getPrivate());
        jws.setPayload(JsonUtil.toJson(claims));
        return jws.getCompactSerialization();
    }

    /**
     * The claims of a JWT signed with the key its x5u header names.
     */
    private static Map<String, Object> verifiedClaims(String jwt) throws Exception {
        JsonWebSignature jws = new JsonWebSignature();
        jws.setCompactSerialization(jwt);
        jws.setKey(EncryptionUtils.parseKey(jws.getHeader(HeaderParameterNames.X509_URL)));
        assertTrue(jws.verifySignature());
        return JsonUtil.parseJson(jws.getPayload());
    }

    private static KeyPair p384KeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp384r1"));
        return generator.generateKeyPair();
    }
}
