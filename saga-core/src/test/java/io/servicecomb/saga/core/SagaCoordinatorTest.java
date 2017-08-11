/*
 * Copyright 2017 Huawei Technologies Co., Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.servicecomb.saga.core;

import static io.servicecomb.saga.core.Compensation.SAGA_START_COMPENSATION;
import static io.servicecomb.saga.core.Transaction.SAGA_START_TRANSACTION;
import static java.util.Collections.emptyMap;
import static java.util.Collections.singleton;
import static java.util.Collections.singletonMap;
import static org.hamcrest.collection.IsIterableContainingInOrder.contains;
import static org.junit.Assert.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.servicecomb.saga.core.application.SagaCoordinator;
import io.servicecomb.saga.core.application.interpreter.JsonRequestInterpreter;
import io.servicecomb.saga.core.application.interpreter.SagaTaskFactory;
import io.servicecomb.saga.infrastructure.EmbeddedEventStore;
import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.hamcrest.TypeSafeMatcher;
import org.junit.Test;

public class SagaCoordinatorTest {

  private final Transport transport = mock(Transport.class);

  private static final String requestJson = "[\n"
      + "  {\n"
      + "    \"id\": \"request-1\",\n"
      + "    \"serviceName\": \"aaa\",\n"
      + "    \"transaction\": {\n"
      + "      \"method\": \"post\",\n"
      + "      \"path\": \"/rest/as\"\n"
      + "    },\n"
      + "    \"compensation\": {\n"
      + "      \"method\": \"delete\",\n"
      + "      \"path\": \"/rest/as\"\n"
      + "    }\n"
      + "  }\n"
      + "]\n";

  private final EventStore eventStore = new EmbeddedEventStore();
  private final PersistentStore persistentStore = mock(PersistentStore.class);

  private final SagaRequest sagaStartRequest = sagaStartRequest();

  private final SagaCoordinator coordinator = new SagaCoordinator(
      eventStore,
      persistentStore,
      new JsonRequestInterpreter(
          new SagaTaskFactory(eventStore, transport)
      )
  );

  @Test
  public void recoverSagaWithEventsFromEventStore() {
    when(persistentStore.findPendingSagaEvents()).thenReturn(
        singletonMap(1L, singleton(
            new EventEnvelope(1L, new SagaStartedEvent(sagaStartRequest)))));

    coordinator.reanimate();

    assertThat(eventStore, contains(
        eventWith(1L, "saga-start", "Saga", "nop", "/", "nop", "/", SagaStartedEvent.class),
        eventWith(2L, "request-1", "aaa", "post", "/rest/as", "delete", "/rest/as", TransactionStartedEvent.class),
        eventWith(3L, "request-1", "aaa", "post", "/rest/as", "delete", "/rest/as", TransactionEndedEvent.class),
        eventWith(4L, "saga-end", "Saga", "nop", "/", "nop", "/", SagaEndedEvent.class)
    ));

    verify(transport).with("aaa", "/rest/as", "post", emptyMap());
  }

  @Test
  public void runSagaWithEventStore() {
    coordinator.run(requestJson);

    assertThat(eventStore, contains(
        eventWith(1L, "saga-start", "Saga", "nop", "/", "nop", "/", SagaStartedEvent.class),
        eventWith(2L, "request-1", "aaa", "post", "/rest/as", "delete", "/rest/as", TransactionStartedEvent.class),
        eventWith(3L, "request-1", "aaa", "post", "/rest/as", "delete", "/rest/as", TransactionEndedEvent.class),
        eventWith(4L, "saga-end", "Saga", "nop", "/", "nop", "/", SagaEndedEvent.class)
    ));

    verify(transport).with("aaa", "/rest/as", "post", emptyMap());
  }

  private SagaRequest sagaStartRequest() {
    return new SagaStartTask(
        requestJson,
        eventStore);
  }

  private Matcher<EventEnvelope> eventWith(
      long eventId,
      String requestId,
      String serviceName,
      String transactionMethod,
      String transactionPath,
      String compensationMethod,
      String compensationPath,
      Class<?> type) {

    return new TypeSafeMatcher<EventEnvelope>() {
      @Override
      protected boolean matchesSafely(EventEnvelope envelope) {
        SagaRequest request = envelope.event.payload();
        return envelope.id == eventId
            && requestId.equals(request.id())
            && envelope.event.getClass().equals(type)
            && request.serviceName().equals(serviceName)
            && request.transaction().path().equals(transactionPath)
            && request.transaction().method().equals(transactionMethod)
            && request.compensation().path().equals(compensationPath)
            && request.compensation().method().equals(compensationMethod);
      }

      @Override
      protected void describeMismatchSafely(EventEnvelope item, Description mismatchDescription) {
        SagaRequest request = item.event.payload();
        mismatchDescription.appendText(
            "EventEnvelope {id=" + item.id
                + "SagaRequest {id=" + request.id()
                + "serviceName=" + request.serviceName()
                + ", transaction=" + request.transaction().method() + ":" + request.transaction().path()
                + ", compensation=" + request.compensation().method() + ":" + request.compensation().path());
      }

      @Override
      public void describeTo(Description description) {
        description.appendText(
            "EventEnvelope {id=" + eventId
                + "SagaRequest {id=" + requestId
                + "serviceName=" + serviceName
                + ", transaction=" + transactionMethod + ":" + transactionPath
                + ", compensation=" + compensationMethod + ":" + compensationPath);
      }
    };
  }
}