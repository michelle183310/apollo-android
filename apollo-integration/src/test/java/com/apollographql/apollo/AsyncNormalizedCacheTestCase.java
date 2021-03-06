package com.apollographql.apollo;

import com.apollographql.apollo.api.Response;
import com.apollographql.apollo.cache.normalized.lru.EvictionPolicy;
import com.apollographql.apollo.cache.normalized.lru.LruNormalizedCacheFactory;
import com.apollographql.apollo.exception.ApolloException;
import com.apollographql.apollo.fetcher.ApolloResponseFetchers;
import com.apollographql.apollo.integration.normalizer.EpisodeHeroNameQuery;
import com.apollographql.apollo.integration.normalizer.type.Episode;
import com.apollographql.apollo.rx2.Rx2Apollo;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nonnull;

import io.reactivex.functions.Predicate;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;

import static com.google.common.truth.Truth.assertThat;
import static junit.framework.Assert.fail;

public class AsyncNormalizedCacheTestCase {
  private static final int TIME_OUT_SECONDS = 3;

  private ApolloClient apolloClient;
  private MockWebServer server;

  @Before public void setUp() {
    server = new MockWebServer();

    OkHttpClient okHttpClient = new OkHttpClient.Builder().build();

    apolloClient = ApolloClient.builder()
        .serverUrl(server.url("/"))
        .okHttpClient(okHttpClient)
        .normalizedCache(new LruNormalizedCacheFactory(EvictionPolicy.NO_EVICTION), new IdFieldCacheKeyResolver())
        .build();
  }

  @After public void tearDown() {
    try {
      server.shutdown();
    } catch (IOException ignored) {
    }
  }

  private MockResponse mockResponse(String fileName) throws IOException, ApolloException {
    return new MockResponse().setChunkedBody(Utils.readFileToString(getClass(), "/" + fileName), 32);
  }

  @Test public void testAsync() throws IOException, InterruptedException, ApolloException {
    EpisodeHeroNameQuery query = EpisodeHeroNameQuery.builder().episode(Episode.EMPIRE).build();

    server.enqueue(mockResponse("HeroNameResponse.json"));
    Rx2Apollo.from(apolloClient.query(query))
        .test()
        .awaitDone(TIME_OUT_SECONDS, TimeUnit.SECONDS)
        .assertValue(new Predicate<Response<EpisodeHeroNameQuery.Data>>() {
          @Override public boolean test(Response<EpisodeHeroNameQuery.Data> dataResponse) throws Exception {
            return !dataResponse.hasErrors();
          }
        });

    for (int i = 0; i < 500; i++) {
      server.enqueue(mockResponse("HeroNameResponse.json"));
    }

    final CountDownLatch latch = new CountDownLatch(1000);
    for (int i = 0; i < 1000; i++) {
      apolloClient.query(query).responseFetcher(i % 2 == 0 ? ApolloResponseFetchers.NETWORK_FIRST
          : ApolloResponseFetchers.CACHE_ONLY)
          .enqueue(new ApolloCall.Callback<EpisodeHeroNameQuery.Data>() {
            @Override public void onResponse(@Nonnull Response<EpisodeHeroNameQuery.Data> response) {
              assertThat(response.hasErrors()).isFalse();
              latch.countDown();
            }

            @Override public void onFailure(@Nonnull ApolloException e) {
              fail("unexpected error: " + e);
              latch.countDown();
            }
          });
    }

    latch.await(5, TimeUnit.SECONDS);
  }
}
