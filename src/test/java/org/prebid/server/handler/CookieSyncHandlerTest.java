package org.prebid.server.handler;

import com.fasterxml.jackson.core.JsonParseException;
import io.netty.util.AsciiString;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.DecodeException;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.prebid.server.VertxTest;
import org.prebid.server.bidder.BidderCatalog;
import org.prebid.server.bidder.appnexus.AppnexusUsersyncer;
import org.prebid.server.bidder.rubicon.RubiconUsersyncer;
import org.prebid.server.cookie.UidsCookie;
import org.prebid.server.cookie.UidsCookieService;
import org.prebid.server.cookie.model.UidWithExpiry;
import org.prebid.server.cookie.proto.Uids;
import org.prebid.server.metric.MetricName;
import org.prebid.server.metric.Metrics;
import org.prebid.server.proto.request.CookieSyncRequest;
import org.prebid.server.proto.response.BidderStatus;
import org.prebid.server.proto.response.CookieSyncResponse;
import org.prebid.server.proto.response.UsersyncInfo;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

public class CookieSyncHandlerTest extends VertxTest {

    private static final String RUBICON = "rubicon";

    private static final String APPNEXUS = "appnexus";
    private static final String APPNEXUS_COOKIE = "adnxs";

    @Rule
    public final MockitoRule mockitoRule = MockitoJUnit.rule();

    @Mock
    private UidsCookieService uidsCookieService;
    @Mock
    private BidderCatalog bidderCatalog;
    @Mock
    private RubiconUsersyncer rubiconUsersyncer;
    @Mock
    private AppnexusUsersyncer appnexusUsersyncer;
    @Mock
    private Metrics metrics;
    @Mock
    private RoutingContext routingContext;
    @Mock
    private HttpServerResponse httpResponse;

    private CookieSyncHandler cookieSyncHandler;

    @Before
    public void setUp() {
        given(uidsCookieService.parseFromRequest(any()))
                .willReturn(new UidsCookie(Uids.builder().uids(emptyMap()).build()));

        given(routingContext.response()).willReturn(httpResponse);
        given(httpResponse.putHeader(any(CharSequence.class), any(CharSequence.class))).willReturn(httpResponse);

        cookieSyncHandler = new CookieSyncHandler(uidsCookieService, bidderCatalog, metrics);
    }

    @Test
    public void creationShouldFailOnNullArguments() {
        assertThatNullPointerException().isThrownBy(() -> new CookieSyncHandler(null, null, null));
        assertThatNullPointerException().isThrownBy(() -> new CookieSyncHandler(uidsCookieService, null, null));
        assertThatNullPointerException().isThrownBy(
                () -> new CookieSyncHandler(uidsCookieService, bidderCatalog, null));
    }

    @Test
    public void shouldRespondWithErrorIfOptedOut() {
        // given
        given(uidsCookieService.parseFromRequest(any()))
                .willReturn(new UidsCookie(Uids.builder().uids(emptyMap()).optout(true).build()));

        given(httpResponse.setStatusCode(anyInt())).willReturn(httpResponse);
        given(httpResponse.setStatusMessage(anyString())).willReturn(httpResponse);

        // when
        cookieSyncHandler.handle(routingContext);

        // then
        verify(httpResponse).setStatusCode(eq(401));
        verify(httpResponse).setStatusMessage(eq("User has opted out"));
        verify(httpResponse).end();
        verifyNoMoreInteractions(httpResponse, bidderCatalog);
    }

    @Test
    public void shouldRespondWithErrorIfRequestBodyCouldNotBeParsed() {
        // given
        given(routingContext.getBodyAsJson())
                .willThrow(new DecodeException("Could not parse", new JsonParseException(null, (String) null)));

        given(httpResponse.setStatusCode(anyInt())).willReturn(httpResponse);
        given(httpResponse.setStatusMessage(anyString())).willReturn(httpResponse);

        // when
        cookieSyncHandler.handle(routingContext);

        // then
        verify(httpResponse).setStatusCode(eq(400));
        verify(httpResponse).setStatusMessage(eq("JSON parse failed"));
        verify(httpResponse).end();
        verifyNoMoreInteractions(httpResponse, bidderCatalog);
    }

    @Test
    public void shouldRespondWithErrorIfRequestBodyIsMissing() {
        // given
        given(routingContext.getBodyAsJson()).willReturn(null);

        given(httpResponse.setStatusCode(anyInt())).willReturn(httpResponse);

        // when
        cookieSyncHandler.handle(routingContext);

        // then
        verify(httpResponse).setStatusCode(eq(400));
        verify(httpResponse).end();
        verifyNoMoreInteractions(httpResponse, bidderCatalog);
    }

    @Test
    public void shouldRespondWithExpectedHeaders() {
        // given
        given(routingContext.getBodyAsJson())
                .willReturn(JsonObject.mapFrom(CookieSyncRequest.of(emptyList())));

        // when
        cookieSyncHandler.handle(routingContext);

        // then
        verify(httpResponse)
                .putHeader(eq(new AsciiString("Content-Type")), eq(new AsciiString("application/json")));
    }

    @Test
    public void shouldRespondWithSomeBidderStatusesIfSomeUidsMissingInCookies() throws IOException {
        // given
        final Map<String, UidWithExpiry> uids = new HashMap<>();
        uids.put(RUBICON, UidWithExpiry.live("J5VLCWQP-26-CWFT"));
        given(uidsCookieService.parseFromRequest(any())).willReturn(new UidsCookie(Uids.builder().uids(uids).build()));

        given(routingContext.getBodyAsJson()).willReturn(JsonObject.mapFrom(
                CookieSyncRequest.of(asList(RUBICON, APPNEXUS))));

        givenUsersyncersReturningFamilyName();

        final UsersyncInfo appnexusUsersyncInfo = UsersyncInfo.of("http://adnxsexample.com", "redirect", false);
        given(bidderCatalog.usersyncerByName(APPNEXUS).usersyncInfo()).willReturn(appnexusUsersyncInfo);

        // when
        cookieSyncHandler.handle(routingContext);

        // then
        final CookieSyncResponse cookieSyncResponse = captureCookieSyncResponse();
        assertThat(cookieSyncResponse).isEqualTo(CookieSyncResponse.of("ok",
                singletonList(BidderStatus.builder()
                        .bidder(APPNEXUS)
                        .noCookie(true)
                        .usersync(appnexusUsersyncInfo)
                        .build())));
    }

    @Test
    public void shouldRespondWithBidderStatusForAllBiddersIfBiddersListOmittedInRequest() throws IOException {

        // given
        given(routingContext.getBodyAsJson()).willReturn(JsonObject.mapFrom(
                CookieSyncRequest.of(null)));

        givenUsersyncersReturningFamilyName();
        final UsersyncInfo appnexusUsersyncInfo = UsersyncInfo.of("http://adnxsexample.com", "redirect", false);
        final UsersyncInfo rubiconUsersyncInfo = UsersyncInfo.of("http://rubiconexample.com", "redirect", false);

        given(appnexusUsersyncer.usersyncInfo()).willReturn(appnexusUsersyncInfo);
        given(rubiconUsersyncer.usersyncInfo()).willReturn(rubiconUsersyncInfo);

        given(bidderCatalog.names()).willReturn(new HashSet<>(asList(APPNEXUS, RUBICON)));

        // when
        cookieSyncHandler.handle(routingContext);

        // then
        final CookieSyncResponse cookieSyncResponse = captureCookieSyncResponse();
        assertThat(cookieSyncResponse).isEqualTo(CookieSyncResponse.of("no_cookie",
                asList(BidderStatus.builder().bidder(APPNEXUS).noCookie(true).usersync(appnexusUsersyncInfo).build(),
                        BidderStatus.builder().bidder(RUBICON).noCookie(true).usersync(rubiconUsersyncInfo).build())));
    }

    @Test
    public void shouldRespondWithNoBidderStatusesIfAllUidsPresentInCookies() throws IOException {
        // given
        final Map<String, UidWithExpiry> uids = new HashMap<>();
        uids.put(RUBICON, UidWithExpiry.live("J5VLCWQP-26-CWFT"));
        uids.put(APPNEXUS_COOKIE, UidWithExpiry.live("12345"));
        given(uidsCookieService.parseFromRequest(any())).willReturn(new UidsCookie(Uids.builder().uids(uids).build()));

        given(routingContext.getBodyAsJson()).willReturn(JsonObject.mapFrom(
                CookieSyncRequest.of(asList(RUBICON, APPNEXUS))));

        givenUsersyncersReturningFamilyName();

        // when
        cookieSyncHandler.handle(routingContext);

        // then
        final CookieSyncResponse cookieSyncResponse = captureCookieSyncResponse();
        assertThat(cookieSyncResponse).isEqualTo(CookieSyncResponse.of("ok", emptyList()));
    }

    @Test
    public void shouldTolerateUnsupportedBidder() throws IOException {
        // given
        final Map<String, UidWithExpiry> uids = new HashMap<>();
        uids.put(RUBICON, UidWithExpiry.live("J5VLCWQP-26-CWFT"));
        uids.put(APPNEXUS_COOKIE, UidWithExpiry.live("12345"));
        given(uidsCookieService.parseFromRequest(any())).willReturn(new UidsCookie(Uids.builder().uids(uids).build()));

        given(routingContext.getBodyAsJson()).willReturn(JsonObject.mapFrom(
                CookieSyncRequest.of(asList(RUBICON, "unsupported"))));

        givenUsersyncersReturningFamilyName();

        given(bidderCatalog.isValidName("unsupported")).willReturn(false);

        // when
        cookieSyncHandler.handle(routingContext);

        // then
        final CookieSyncResponse cookieSyncResponse = captureCookieSyncResponse();
        assertThat(cookieSyncResponse).isEqualTo(CookieSyncResponse.of("ok", emptyList()));
    }

    @Test
    public void shouldRespondWithNoCookieStatusIfNoLiveUids() throws IOException {
        // given
        final Map<String, UidWithExpiry> uids = new HashMap<>();
        uids.put(APPNEXUS_COOKIE, UidWithExpiry.expired("12345"));
        given(uidsCookieService.parseFromRequest(any())).willReturn(new UidsCookie(Uids.builder().uids(uids).build()));

        given(routingContext.getBodyAsJson()).willReturn(JsonObject.mapFrom(
                CookieSyncRequest.of(singletonList(APPNEXUS))));

        givenUsersyncersReturningFamilyName();

        final UsersyncInfo appnexusUsersyncInfo = UsersyncInfo.of("http://adnxsexample.com", "redirect", false);
        given(bidderCatalog.usersyncerByName(APPNEXUS).usersyncInfo()).willReturn(appnexusUsersyncInfo);

        // when
        cookieSyncHandler.handle(routingContext);

        // then
        final CookieSyncResponse cookieSyncResponse = captureCookieSyncResponse();
        assertThat(cookieSyncResponse).isEqualTo(CookieSyncResponse.of("no_cookie",
                singletonList(BidderStatus.builder()
                        .bidder(APPNEXUS)
                        .noCookie(true)
                        .usersync(appnexusUsersyncInfo)
                        .build())));
    }

    @Test
    public void shouldIncrementMetrics() {
        // given
        given(routingContext.getBodyAsJson())
                .willReturn(JsonObject.mapFrom(CookieSyncRequest.of(emptyList())));

        // when
        cookieSyncHandler.handle(routingContext);

        // then
        verify(metrics).incCounter(eq(MetricName.cookie_sync_requests));
    }

    private void givenUsersyncersReturningFamilyName() {
        given(bidderCatalog.usersyncerByName(eq(RUBICON))).willReturn(rubiconUsersyncer);
        given(bidderCatalog.isValidName(eq(RUBICON))).willReturn(true);
        given(bidderCatalog.usersyncerByName(eq(APPNEXUS))).willReturn(appnexusUsersyncer);
        given(bidderCatalog.isValidName(eq(APPNEXUS))).willReturn(true);

        given(rubiconUsersyncer.cookieFamilyName()).willReturn(RUBICON);

        given(appnexusUsersyncer.cookieFamilyName()).willReturn(APPNEXUS_COOKIE);
    }

    private CookieSyncResponse captureCookieSyncResponse() throws IOException {
        final ArgumentCaptor<String> cookieSyncResponseCaptor = ArgumentCaptor.forClass(String.class);
        verify(httpResponse).end(cookieSyncResponseCaptor.capture());
        return mapper.readValue(cookieSyncResponseCaptor.getValue(), CookieSyncResponse.class);
    }
}
