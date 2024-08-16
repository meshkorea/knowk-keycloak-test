/*
 * Copyright 2024 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.keycloak.models.sessions.infinispan.changes;

import org.infinispan.Cache;
import org.jboss.logging.Logger;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserSessionModel;
import org.keycloak.models.UserSessionProvider;
import org.keycloak.models.session.UserSessionPersisterProvider;
import org.keycloak.models.sessions.infinispan.PersistentUserSessionProvider;
import org.keycloak.models.sessions.infinispan.SessionFunction;
import org.keycloak.models.sessions.infinispan.entities.SessionEntity;
import org.keycloak.models.sessions.infinispan.entities.UserSessionEntity;
import org.keycloak.models.sessions.infinispan.remotestore.RemoteCacheInvoker;

import java.util.concurrent.ArrayBlockingQueue;

public class UserSessionPersistentChangelogBasedTransaction extends PersistentSessionsChangelogBasedTransaction<String, UserSessionEntity> {

    private static final Logger LOG = Logger.getLogger(UserSessionPersistentChangelogBasedTransaction.class);

    public UserSessionPersistentChangelogBasedTransaction(KeycloakSession session,
                                                          Cache<String, SessionEntityWrapper<UserSessionEntity>> cache,
                                                          Cache<String, SessionEntityWrapper<UserSessionEntity>> offlineCache,
                                                          RemoteCacheInvoker remoteCacheInvoker,
                                                          SessionFunction<UserSessionEntity> lifespanMsLoader,
                                                          SessionFunction<UserSessionEntity> maxIdleTimeMsLoader,
                                                          SessionFunction<UserSessionEntity> offlineLifespanMsLoader,
                                                          SessionFunction<UserSessionEntity> offlineMaxIdleTimeMsLoader,
                                                          ArrayBlockingQueue<PersistentUpdate> batchingQueue,
                                                          SerializeExecutionsByKey<String> serializerOnline,
                                                          SerializeExecutionsByKey<String> serializerOffline) {
        super(session, cache, offlineCache, remoteCacheInvoker, lifespanMsLoader, maxIdleTimeMsLoader, offlineLifespanMsLoader, offlineMaxIdleTimeMsLoader, batchingQueue, serializerOnline, serializerOffline);
    }

    public SessionEntityWrapper<UserSessionEntity> get(RealmModel realm, String key, boolean offline) {
        LOG.info("mazend: getUserSession2.1.1");
        SessionUpdatesList<UserSessionEntity> myUpdates = getUpdates(offline).get(key);

        LOG.infof("mazend: getUserSession2.1.1 getUpdates(offline).keySet().size() = %d", getUpdates(offline).keySet().size());
        for (String k: getUpdates(offline).keySet()) {
            LOG.infof("mazend: getUserSession2.1.1 key = %s", k);
        }

//        LOG.infof("mazend: getUserSession2.1.1 myUpdates.getUpdateTasks().size() = %d", myUpdates.getUpdateTasks().size());

        if (myUpdates == null) {
            SessionEntityWrapper<UserSessionEntity> wrappedEntity = null;
            wrappedEntity = getCache(offline).get(key);

            if (wrappedEntity == null) {
                LOG.infof("user-session not found in cache for sessionId=%s offline=%s, loading from persister", key, offline); // 여기 탐
                wrappedEntity = getSessionEntityFromPersister(realm, key, offline);
            } else {
                LOG.infof("user-session found in cache for sessionId=%s offline=%s %s", key, offline, wrappedEntity.getEntity().getLastSessionRefresh());
            }

            if (wrappedEntity == null) {
                LOG.infof("user-session not found in persister for sessionId=%s offline=%s", key, offline);
                return null;
            }

            // Cache does not contain the offline flag value so adding it
            wrappedEntity.getEntity().setOffline(offline);

            RealmModel realmFromSession = kcSession.realms().getRealm(wrappedEntity.getEntity().getRealmId());
            if (!realmFromSession.getId().equals(realm.getId())) {
                LOG.infof("Realm mismatch for session %s. Expected realm %s, but found realm %s", wrappedEntity.getEntity(), realm.getId(), realmFromSession.getId());
                return null;
            }

            myUpdates = new SessionUpdatesList<>(realm, wrappedEntity);
            getUpdates(offline).put(key, myUpdates);

            return wrappedEntity;
        } else {
            LOG.info("mazend: If entity is scheduled for remove, we don't return it.");
            // If entity is scheduled for remove, we don't return it.
            boolean scheduledForRemove = myUpdates.getUpdateTasks().stream()
                    .map(SessionUpdateTask::getOperation)
                    .anyMatch(SessionUpdateTask.CacheOperation.REMOVE::equals);

            LOG.info("mazend: scheduledForRemove = " + scheduledForRemove);
            return scheduledForRemove ? null : myUpdates.getEntityWrapper();
        }
    }

    private SessionEntityWrapper<UserSessionEntity> getSessionEntityFromPersister(RealmModel realm, String key, boolean offline) {
        LOG.infof("mazend: getSessionEntityFromPersister: key = %s, offline = %b", key, offline);
        UserSessionPersisterProvider persister = kcSession.getProvider(UserSessionPersisterProvider.class);
        LOG.infof("mazend: getSessionEntityFromPersister: persister = " + persister);
        UserSessionModel persistentUserSession = persister.loadUserSession(realm, key, offline);
        LOG.infof("mazend: getSessionEntityFromPersister: persistentUserSession = " + persistentUserSession);

        if (persistentUserSession == null) {
            return null;
        }

        return importUserSession(persistentUserSession);
    }

    private SessionEntityWrapper<UserSessionEntity> importUserSession(UserSessionModel persistentUserSession) {
        String sessionId = persistentUserSession.getId();
        boolean offline = persistentUserSession.isOffline();

        LOG.infof("mazend: importUserSession: sessionId = %s, offline = %b", sessionId, offline);

        if (isScheduledForRemove(sessionId, offline)) {
            LOG.infof("mazend: isScheduledForRemove == true");
            return null;
        }

        LOG.infof("Attempting to import user-session for sessionId=%s offline=%s", sessionId, offline);
        SessionEntityWrapper<UserSessionEntity> ispnUserSessionEntity = ((PersistentUserSessionProvider) kcSession.getProvider(UserSessionProvider.class)).importUserSession(persistentUserSession, offline);

        if (ispnUserSessionEntity != null) {
            LOG.infof("user-session found after import for sessionId=%s offline=%s", sessionId, offline);
            return ispnUserSessionEntity;
        }

        LOG.infof("user-session could not be found after import for sessionId=%s offline=%s", sessionId, offline); // <-- 여기에 걸림
        return null;
    }

    public boolean isScheduledForRemove(String key, boolean offline) {
        LOG.infof("mazend: isScheduledForRemove: key = %s, offline = %b", key, offline);
        return isScheduledForRemove(getUpdates(offline).get(key));
    }

    private static <V extends SessionEntity> boolean isScheduledForRemove(SessionUpdatesList<V> myUpdates) {
        LOG.infof("mazend: isScheduledForRemove: myUpdates = " + myUpdates);
        if (myUpdates == null) {
            LOG.infof("mazend: isScheduledForRemove: myUpdates == null");
            return false;
        }
        // If entity is scheduled for remove, we don't return it.

        LOG.infof("mazend: isScheduledForRemove: myUpdates != null");
        if (!myUpdates.getUpdateTasks().isEmpty()) {
            for (SessionUpdateTask updateTask : myUpdates.getUpdateTasks()) {
                LOG.infof("mazend: updateTask = " + updateTask);
                LOG.infof("mazend: updateTask = " + updateTask.getOperation());
            }
        }

        return myUpdates.getUpdateTasks()
                .stream()
                .anyMatch(task -> task.getOperation() == SessionUpdateTask.CacheOperation.REMOVE);
    }

}
