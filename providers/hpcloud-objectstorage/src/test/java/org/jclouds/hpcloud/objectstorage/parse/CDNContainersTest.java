/**
 * Licensed to jclouds, Inc. (jclouds) under one or more
 * contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  jclouds licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.jclouds.hpcloud.objectstorage.parse;

import java.net.URI;

import javax.ws.rs.Consumes;
import javax.ws.rs.core.MediaType;

import org.jclouds.hpcloud.objectstorage.domain.CDNContainer;
import org.jclouds.json.BaseItemParserTest;
import org.testng.annotations.Test;

import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableSet;

/**
 * @author Adrian Cole
 */
@Test(groups = "unit", testName = "CDNContainersTest")
public class CDNContainersTest extends BaseItemParserTest<FluentIterable<CDNContainer>> {

   @Override
   public String resource() {
      return "/test_list_cdn.json";
   }

   @Override
   @Consumes(MediaType.APPLICATION_JSON)
   public FluentIterable<CDNContainer> expected() {
      return FluentIterable.from(ImmutableSet.of(
               CDNContainer.builder().name("hpcloud-blobstore.testCDNOperationsContainerWithCDN").CDNEnabled(false)
                        .ttl(3600).CDNUri(URI.create("https://cdnmgmt.hpcloud.net:8080/v1/AUTH_test/")).build(),
               CDNContainer.builder().name("hpcloud-blobstore5").CDNEnabled(true).ttl(28800)
                        .CDNUri(URI.create("https://cdnmgmt.hpcloud.net:8080/v1/AUTH_test/")).build(),
               CDNContainer.builder().name("hpcloud-cfcdnint.testCDNOperationsContainerWithCDN").CDNEnabled(false)
                        .ttl(3600).CDNUri(URI.create("https://cdnmgmt.hpcloud.net:8080/v1/AUTH_test/")).build()));
   }
}
