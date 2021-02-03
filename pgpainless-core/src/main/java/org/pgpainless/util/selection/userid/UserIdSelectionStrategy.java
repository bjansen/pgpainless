/*
 * Copyright 2021 Paul Schaub.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.pgpainless.util.selection.userid;

import java.util.ArrayList;
import java.util.List;

import org.bouncycastle.openpgp.PGPKeyRing;
import org.pgpainless.PGPainless;

public abstract class UserIdSelectionStrategy {

    protected abstract boolean accept(String userId);

    public List<String> selectUserIds(PGPKeyRing keyRing) {
        List<String> userIds = PGPainless.inspectKeyRing(keyRing).getValidUserIds();
        return selectUserIds(userIds);
    }

    public List<String> selectUserIds(List<String> userIds) {
        List<String> selected = new ArrayList<>();
        for (String userId : userIds) {
            if (accept(userId)) {
                selected.add(userId);
            }
        }
        return selected;
    }

    public String firstMatch(PGPKeyRing keyRing) {
        return firstMatch(selectUserIds(keyRing));
    }

    public String firstMatch(List<String> userIds) {
        for (String userId : userIds) {
            if (accept(userId)) {
                return userId;
            }
        }
        return null;
    }

    public static UserIdSelectionStrategy containsSubstring(String query) {
        return new UserIdSelectionStrategy() {
            @Override
            protected boolean accept(String userId) {
                return userId.contains(query);
            }
        };
    }

    public static UserIdSelectionStrategy exactMatch(String query) {
        return new UserIdSelectionStrategy() {
            @Override
            protected boolean accept(String userId) {
                return userId.equals(query);
            }
        };
    }

    public static UserIdSelectionStrategy startsWith(String substring) {
        return new UserIdSelectionStrategy() {
            @Override
            protected boolean accept(String userId) {
                return userId.startsWith(substring);
            }
        };
    }

    public static UserIdSelectionStrategy containsEmailAddress(String email) {
        return containsSubstring(email.matches("^<.+>$") ? email : '<' + email + '>');
    }
}
