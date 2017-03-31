/*
 * Copyright (c) Members of the EGEE Collaboration. 2006-2010.
 * See http://www.eu-egee.org/partners/ for details on the copyright holders.
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

package org.glite.authz.pep.pip.provider.authnprofilespip;

import java.util.Set;

import javax.security.auth.x500.X500Principal;

/**
 * An {@link AuthenticationProfile} represents a named grouping of CA subjects that share common
 * properties (i.e. same Level Of Assurance).
 */
public interface AuthenticationProfile {

  /**
   * Returns the alias linked to this authentication profile
   * 
   * @return the alias for the authentication profile
   */
  String getAlias();

  /**
   * Returns the set of certificate authority subjects in this authentication profile
   * 
   * @return a (possibly empty) {@link Set} of {@link X500Principal} objects representing the
   *         certificate authority subjects in this authentication profile
   */
  Set<X500Principal> getCASubjects();
}
