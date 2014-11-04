/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jclouds.googlecomputeengine.compute;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.Iterables.contains;
import static com.google.common.collect.Iterables.filter;
import static com.google.common.collect.Iterables.transform;
import static com.google.common.collect.Iterables.tryFind;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.jclouds.googlecomputeengine.GoogleComputeEngineConstants.CENTOS_PROJECT;
import static org.jclouds.googlecomputeengine.GoogleComputeEngineConstants.DEBIAN_PROJECT;
import static org.jclouds.googlecomputeengine.GoogleComputeEngineConstants.GCE_BOOT_DISK_SUFFIX;
import static org.jclouds.googlecomputeengine.GoogleComputeEngineConstants.GCE_DELETE_BOOT_DISK_METADATA_KEY;
import static org.jclouds.googlecomputeengine.GoogleComputeEngineConstants.GCE_IMAGE_METADATA_KEY;
import static org.jclouds.googlecomputeengine.GoogleComputeEngineConstants.OPERATION_COMPLETE_INTERVAL;
import static org.jclouds.googlecomputeengine.GoogleComputeEngineConstants.OPERATION_COMPLETE_TIMEOUT;
import static org.jclouds.googlecomputeengine.domain.Instance.NetworkInterface.AccessConfig.Type;
import static org.jclouds.googlecomputeengine.internal.ListPages.concat;
import static org.jclouds.googlecomputeengine.predicates.InstancePredicates.isBootDisk;
import static org.jclouds.util.Predicates2.retry;

import java.net.URI;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.inject.Inject;
import javax.inject.Named;

import org.jclouds.collect.Memoized;
import org.jclouds.compute.ComputeServiceAdapter;
import org.jclouds.compute.domain.Hardware;
import org.jclouds.compute.domain.Template;
import org.jclouds.compute.options.TemplateOptions;
import org.jclouds.domain.Location;
import org.jclouds.domain.LoginCredentials;
import org.jclouds.googlecomputeengine.GoogleComputeEngineApi;
import org.jclouds.googlecomputeengine.compute.domain.InstanceInZone;
import org.jclouds.googlecomputeengine.compute.domain.MachineTypeInZone;
import org.jclouds.googlecomputeengine.compute.domain.SlashEncodedIds;
import org.jclouds.googlecomputeengine.compute.functions.FirewallTagNamingConvention;
import org.jclouds.googlecomputeengine.compute.options.GoogleComputeEngineTemplateOptions;
import org.jclouds.googlecomputeengine.config.UserProject;
import org.jclouds.googlecomputeengine.domain.Disk;
import org.jclouds.googlecomputeengine.domain.Image;
import org.jclouds.googlecomputeengine.domain.Instance;
import org.jclouds.googlecomputeengine.domain.Instance.AttachedDisk;
import org.jclouds.googlecomputeengine.domain.Instance.AttachedDisk.Mode;
import org.jclouds.googlecomputeengine.domain.ListPage;
import org.jclouds.googlecomputeengine.domain.MachineType;
import org.jclouds.googlecomputeengine.domain.Operation;
import org.jclouds.googlecomputeengine.domain.Zone;
import org.jclouds.googlecomputeengine.domain.templates.InstanceTemplate;
import org.jclouds.googlecomputeengine.domain.templates.InstanceTemplate.PersistentDisk;
import org.jclouds.googlecomputeengine.features.InstanceApi;
import org.jclouds.googlecomputeengine.options.DiskCreationOptions;

import com.google.common.base.Function;
import com.google.common.base.Objects;
import com.google.common.base.Predicate;
import com.google.common.base.Supplier;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.primitives.Ints;
import com.google.common.util.concurrent.Atomics;
import com.google.common.util.concurrent.UncheckedTimeoutException;

public final class GoogleComputeEngineServiceAdapter implements ComputeServiceAdapter<InstanceInZone, MachineTypeInZone, Image, Zone> {

   private final GoogleComputeEngineApi api;
   private final Supplier<String> userProject;
   private final Supplier<Map<URI, ? extends Location>> zones;
   private final Function<TemplateOptions, ImmutableMap.Builder<String, String>> metatadaFromTemplateOptions;
   private final Predicate<AtomicReference<Operation>> retryOperationDonePredicate;
   private final long operationCompleteCheckInterval;
   private final long operationCompleteCheckTimeout;
   private final FirewallTagNamingConvention.Factory firewallTagNamingConvention;

   @Inject GoogleComputeEngineServiceAdapter(GoogleComputeEngineApi api,
                                            @UserProject Supplier<String> userProject,
                                            Function<TemplateOptions,
                                                    ImmutableMap.Builder<String, String>> metatadaFromTemplateOptions,
                                            @Named("zone") Predicate<AtomicReference<Operation>> operationDonePredicate,
                                            @Named(OPERATION_COMPLETE_INTERVAL) Long operationCompleteCheckInterval,
                                            @Named(OPERATION_COMPLETE_TIMEOUT) Long operationCompleteCheckTimeout,
                                            @Memoized Supplier<Map<URI, ? extends Location>> zones,
                                            FirewallTagNamingConvention.Factory firewallTagNamingConvention) {
      this.api = api;
      this.userProject = userProject;
      this.metatadaFromTemplateOptions = metatadaFromTemplateOptions;
      this.operationCompleteCheckInterval = operationCompleteCheckInterval;
      this.operationCompleteCheckTimeout = operationCompleteCheckTimeout;
      this.retryOperationDonePredicate = retry(operationDonePredicate, operationCompleteCheckTimeout,
                                               operationCompleteCheckInterval, TimeUnit.MILLISECONDS);
      this.zones = zones;
      this.firewallTagNamingConvention = firewallTagNamingConvention;
   }

   @Override
   public NodeAndInitialCredentials<InstanceInZone> createNodeWithGroupEncodedIntoName(
           final String group, final String name, final Template template) {

      checkNotNull(template, "template");

      GoogleComputeEngineTemplateOptions options = GoogleComputeEngineTemplateOptions.class.cast(template.getOptions()).clone();
      checkState(options.getNetwork().isPresent(), "network was not present in template options");
      Hardware hardware = checkNotNull(template.getHardware(), "hardware must be set");

      checkNotNull(hardware.getUri(), "hardware must have a URI");
      checkNotNull(template.getImage().getUri(), "image URI is null");

      // Note that the ordering is significant here - the first disk must be the boot disk.
      List<PersistentDisk> disks = Lists.newArrayList();

      if (!tryFind(options.getDisks(), isBootDisk()).isPresent()) {
         Disk bootDisk = createBootDisk(template, name);

         disks.add(new PersistentDisk(Mode.READ_WRITE,
                                      bootDisk.selfLink(),
                                      null,
                                      true,
                                      true));
      }

      disks.addAll(options.getDisks());

      InstanceTemplate instanceTemplate = new InstanceTemplate().machineType(hardware.getUri());

      if (options.isEnableNat()) {
         instanceTemplate.addNetworkInterface(options.getNetwork().get(), Type.ONE_TO_ONE_NAT);
      } else {
         instanceTemplate.addNetworkInterface(options.getNetwork().get());
      }

      instanceTemplate.disks(disks);

      LoginCredentials credentials = getFromImageAndOverrideIfRequired(template.getImage(), options);

      ImmutableMap.Builder<String, String> metadataBuilder = metatadaFromTemplateOptions.apply(options);

      metadataBuilder.put(GCE_IMAGE_METADATA_KEY, template.getImage().getUri().toString());

      if (!options.shouldKeepBootDisk()) {
         metadataBuilder.put(GCE_DELETE_BOOT_DISK_METADATA_KEY, Boolean.TRUE.toString());
      }

      instanceTemplate.metadata(metadataBuilder.build());
      instanceTemplate.serviceAccounts(options.getServiceAccounts());

      final InstanceApi instanceApi = api.getInstanceApi(userProject.get());
      final String zone = template.getLocation().getId();
      Operation operation = instanceApi.createInZone(name, zone, instanceTemplate);

      if (options.shouldBlockUntilRunning()) {
         waitOperationDone(operation);
      }

      // some times the newly created instances are not immediately returned
      AtomicReference<Instance> instance = Atomics.newReference();

      retry(new Predicate<AtomicReference<Instance>>() {
         @Override
         public boolean apply(AtomicReference<Instance> input) {
            input.set(instanceApi.getInZone(zone, name));
            return input.get() != null;
         }
      }, operationCompleteCheckTimeout, operationCompleteCheckInterval, MILLISECONDS).apply(instance);

      if (!options.getTags().isEmpty()) {
         Operation tagsOperation = instanceApi
               .setTagsInZone(zone, name, options.getTags(), instance.get().tags().fingerprint());

         waitOperationDone(tagsOperation);

         retry(new Predicate<AtomicReference<Instance>>() {
            @Override
            public boolean apply(AtomicReference<Instance> input) {
               input.set(instanceApi.getInZone(zone, name));
               return input.get() != null;
            }
         }, operationCompleteCheckTimeout, operationCompleteCheckInterval, MILLISECONDS).apply(instance);
      }

      // Add tags for security groups
      final FirewallTagNamingConvention naming = firewallTagNamingConvention.get(group);
      Set<String> tags = FluentIterable.from(Ints.asList(options.getInboundPorts()))
              .transform(new Function<Integer, String>(){
                       @Override
                       public String apply(Integer input) {
                          return input != null
                                  ? naming.name(input)
                                  : null;
                       }
                    })
              .toSet();
      instanceApi.setTagsInZone(zone, instance.get().name(), tags, instance.get().tags().fingerprint());

      InstanceInZone instanceInZone = new InstanceInZone(instance.get(), zone);

      return new NodeAndInitialCredentials<InstanceInZone>(instanceInZone, instanceInZone.slashEncode(), credentials);
   }

   private Disk createBootDisk(Template template, String instanceName) {
      URI imageUri = template.getImage().getUri();

      GoogleComputeEngineTemplateOptions options = GoogleComputeEngineTemplateOptions.class.cast(template.getOptions()).clone();

      int diskSize = options.getBootDiskSize().or(10l).intValue();

      String diskName = instanceName + "-" + GCE_BOOT_DISK_SUFFIX;

      DiskCreationOptions diskCreationOptions = new DiskCreationOptions().sourceImage(imageUri);
      Operation diskOperation = api.getDiskApi(userProject.get())
                                   .createInZone(diskName,
                                                 diskSize,
                                                 template.getLocation().getId(),
                                                 diskCreationOptions);

      waitOperationDone(diskOperation);

      return api.getDiskApi(userProject.get()).getInZone(template.getLocation().getId(),
                                                                   diskName);
   }

   @Override
   public Iterable<MachineTypeInZone> listHardwareProfiles() {
      ImmutableList.Builder<MachineTypeInZone> builder = ImmutableList.builder();

      for (final Location zone : zones.get().values()) {
         for (Iterator<ListPage<MachineType>> i = api.getMachineTypeApi(userProject.get()).listInZone(zone.getId());
               i.hasNext(); ) {
            builder.addAll(FluentIterable.from(i.next()).filter(new Predicate<MachineType>() {
               @Override
               public boolean apply(MachineType input) {
                  return input.deprecated() == null;
               }
            }).transform(new Function<MachineType, MachineTypeInZone>() {

               @Override
               public MachineTypeInZone apply(MachineType arg0) {
                  return new MachineTypeInZone(arg0, arg0.zone());
               }
            }));
         }

      }

      return builder.build();
   }

   @Override
   public Iterable<Image> listImages() {
      return Iterables.concat( //
            concat(api.getImageApi(userProject.get()).list()), //
            concat(api.getImageApi(DEBIAN_PROJECT).list()), //
            concat(api.getImageApi(CENTOS_PROJECT).list()));
   }

   @SuppressWarnings("deprecation")
   @Override
   public Image getImage(String id) {
      return Objects.firstNonNull(api.getImageApi(userProject.get()).get(id),
                                  Objects.firstNonNull(api.getImageApi(DEBIAN_PROJECT).get(id),
                                          api.getImageApi(CENTOS_PROJECT).get(id)));

   }

   @Override
   public Iterable<Zone> listLocations() {
      return concat(api.getZoneApi(userProject.get()).list());
   }

   @Override
   public InstanceInZone getNode(String name) {
      SlashEncodedIds slashEncodedIds = SlashEncodedIds.fromSlashEncoded(name);

      Instance instance = api.getInstanceApi(userProject.get()).getInZone(slashEncodedIds.getFirstId(),
              slashEncodedIds.getSecondId());

      return instance == null ?  null : new InstanceInZone(instance, slashEncodedIds.getFirstId());
   }

   @Override
   public Iterable<InstanceInZone> listNodes() {
      return FluentIterable.from(zones.get().values())
            .transformAndConcat(new Function<Location, Iterable<InstanceInZone>>() {
               @Override
               public Iterable<InstanceInZone> apply(final Location input) {
                  return transform(concat(api.getInstanceApi(userProject.get()).listInZone(input.getId())),
                        new Function<Instance, InstanceInZone>() {
                           @Override public InstanceInZone apply(Instance arg0) {
                              return new InstanceInZone(arg0, input.getId());
                           }
                        });
               }
            }).toList();
   }

   @Override
   public Iterable<InstanceInZone> listNodesByIds(final Iterable<String> ids) {
      return filter(listNodes(), new Predicate<InstanceInZone>() {

         @Override
         public boolean apply(InstanceInZone instanceInZone) {
            return contains(ids, instanceInZone.getInstance().name());
         }
      });
   }

   @Override
   public void destroyNode(final String name) {
      SlashEncodedIds slashEncodedIds = SlashEncodedIds.fromSlashEncoded(name);
      String diskName = null;
      Instance instance = api.getInstanceApi(userProject.get()).getInZone(slashEncodedIds.getFirstId(),
                                                                           slashEncodedIds.getSecondId());
      if (instance != null &&
            "true".equals(instance.metadata().items().get(GCE_DELETE_BOOT_DISK_METADATA_KEY))) {
         for (AttachedDisk input : instance.disks()) {
            if (input.type() == AttachedDisk.Type.PERSISTENT && input.boot()){
               String source = input.source().toASCIIString();
               diskName = source.substring(source.lastIndexOf('/') + 1);
            }
         }
      }
      waitOperationDone(api.getInstanceApi(userProject.get()).deleteInZone(slashEncodedIds.getFirstId(),
              slashEncodedIds.getSecondId()));

      if (diskName != null) {
         waitOperationDone(api.getDiskApi(userProject.get()).deleteInZone(slashEncodedIds.getFirstId(), diskName));
      }
   }

   @Override
   public void rebootNode(final String name) {
      SlashEncodedIds slashEncodedIds = SlashEncodedIds.fromSlashEncoded(name);

      waitOperationDone(api.getInstanceApi(userProject.get()).resetInZone(slashEncodedIds.getFirstId(),
              slashEncodedIds.getSecondId()));
   }

   @Override
   public void resumeNode(String name) {
      throw new UnsupportedOperationException("resume is not supported by GCE");
   }

   @Override
   public void suspendNode(String name) {
      throw new UnsupportedOperationException("suspend is not supported by GCE");
   }

   private LoginCredentials getFromImageAndOverrideIfRequired(org.jclouds.compute.domain.Image image,
                                                              GoogleComputeEngineTemplateOptions options) {
      LoginCredentials defaultCredentials = image.getDefaultCredentials();
      String[] keys = defaultCredentials.getPrivateKey().split(":");
      String publicKey = keys[0];
      String privateKey = keys[1];

      LoginCredentials.Builder credentialsBuilder = defaultCredentials.toBuilder();
      credentialsBuilder.privateKey(privateKey);

      // LoginCredentials from image stores the public key along with the private key in the privateKey field
      // @see GoogleComputePopulateDefaultLoginCredentialsForImageStrategy
      // so if options doesn't have a public key set we set it from the default
      if (options.getPublicKey() == null) {
         options.authorizePublicKey(publicKey);
      }
      if (options.hasLoginPrivateKeyOption()) {
         credentialsBuilder.privateKey(options.getPrivateKey());
      }
      if (options.getLoginUser() != null) {
         credentialsBuilder.identity(options.getLoginUser());
      }
      if (options.hasLoginPasswordOption()) {
         credentialsBuilder.password(options.getLoginPassword());
      }
      if (options.shouldAuthenticateSudo() != null) {
         credentialsBuilder.authenticateSudo(options.shouldAuthenticateSudo());
      }
      LoginCredentials credentials = credentialsBuilder.build();
      options.overrideLoginCredentials(credentials);
      return credentials;
   }

   private void waitOperationDone(Operation operation) {
      AtomicReference<Operation> operationRef = Atomics.newReference(operation);

      // wait for the operation to complete
      if (!retryOperationDonePredicate.apply(operationRef)) {
         throw new UncheckedTimeoutException("operation did not reach DONE state" + operationRef.get());
      }

      // check if the operation failed
      if (operationRef.get().httpErrorStatusCode() != null) {
         throw new IllegalStateException(
               "operation failed. Http Error Code: " + operationRef.get().httpErrorStatusCode() +
                     " HttpError: " + operationRef.get().httpErrorMessage());
      }
   }
}
