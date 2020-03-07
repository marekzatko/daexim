/*
 * Copyright (C) 2016 AT&T Intellectual Property. All rights reserved.
 * Copyright (c) 2016 Brocade Communications Systems, Inc. All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.daexim.impl;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Objects;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Sets;
import com.google.gson.stream.JsonReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.opendaylight.daexim.impl.model.internal.Model;
import org.opendaylight.daexim.impl.model.internal.ModelsNotAvailableException;
import org.opendaylight.mdsal.common.api.LogicalDatastoreType;
import org.opendaylight.mdsal.dom.api.DOMDataBroker;
import org.opendaylight.mdsal.dom.api.DOMDataTreeReadWriteTransaction;
import org.opendaylight.mdsal.dom.api.DOMSchemaService;
import org.opendaylight.yang.gen.v1.urn.opendaylight.daexim.internal.rev160921.ImportOperationResult;
import org.opendaylight.yang.gen.v1.urn.opendaylight.daexim.internal.rev160921.ImportOperationResultBuilder;
import org.opendaylight.yang.gen.v1.urn.opendaylight.daexim.rev160921.DataStoreScope;
import org.opendaylight.yang.gen.v1.urn.opendaylight.daexim.rev160921.ImmediateImportInput;
import org.opendaylight.yangtools.yang.common.QName;
import org.opendaylight.yangtools.yang.common.Revision;
import org.opendaylight.yangtools.yang.data.api.YangInstanceIdentifier;
import org.opendaylight.yangtools.yang.data.api.YangInstanceIdentifier.PathArgument;
import org.opendaylight.yangtools.yang.data.api.schema.NormalizedNode;
import org.opendaylight.yangtools.yang.data.api.schema.NormalizedNodeContainer;
import org.opendaylight.yangtools.yang.data.api.schema.stream.NormalizedNodeStreamWriter;
import org.opendaylight.yangtools.yang.data.codec.gson.JSONCodecFactorySupplier;
import org.opendaylight.yangtools.yang.data.codec.gson.JsonParserStream;
import org.opendaylight.yangtools.yang.data.impl.schema.ImmutableNormalizedNodeStreamWriter;
import org.opendaylight.yangtools.yang.data.impl.schema.builder.api.NormalizedNodeContainerBuilder;
import org.opendaylight.yangtools.yang.data.impl.schema.builder.impl.ImmutableContainerNodeBuilder;
import org.opendaylight.yangtools.yang.model.api.DataSchemaNode;
import org.opendaylight.yangtools.yang.model.api.Module;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ImportTask implements Callable<ImportOperationResult> {
    private static final Logger LOG = LoggerFactory.getLogger(ImportTask.class);
    private static final JSONCodecFactorySupplier CODEC = JSONCodecFactorySupplier.DRAFT_LHOTKA_NETMOD_YANG_JSON_02;
    private final DOMDataBroker dataBroker;
    private final DOMSchemaService schemaService;
    private final boolean mustValidate;
    private final DataStoreScope clearScope;
    private final boolean strictDataConsistency;
    private final Consumer<Void> callback;
    private final boolean isBooting;
    @VisibleForTesting
    final ListMultimap<LogicalDatastoreType, File> dataFiles;
    private final Predicate<File> dataFileFilter;

    private final class DataFileMatcher implements Predicate<File> {
        final Pattern pattern;

        private DataFileMatcher(final String pattern) {
            this.pattern = Pattern.compile(pattern);
        }

        @Override
        public boolean test(final File file) {
            return pattern.matcher(file.getName()).matches();
        }
    }

    public ImportTask(final ImmediateImportInput input, DOMDataBroker domDataBroker,
            final DOMSchemaService schemaService, boolean isBooting, Consumer<Void> callback) {
        this.dataBroker = domDataBroker;
        this.schemaService = schemaService;
        this.mustValidate = input.isCheckModels() != null && input.isCheckModels();
        this.clearScope = input.getClearStores();
        this.strictDataConsistency = input.isStrictDataConsistency();
        this.isBooting = isBooting;
        this.callback = callback;
        dataFiles = ArrayListMultimap.create(LogicalDatastoreType.values().length, 4);
        this.dataFileFilter = Strings.isNullOrEmpty(input.getFileNameFilter()) ? t -> true
                : new DataFileMatcher(input.getFileNameFilter());
        collectFiles();
        LOG.info("Created import task : {}, collected dump files : {}", input, dataFiles);
    }

    @Override
    @SuppressWarnings("checkstyle:IllegalCatch")
    public ImportOperationResult call() throws Exception {
        callback.accept(null);
        try {
            importInternal();
            return new ImportOperationResultBuilder().setResult(true).build();
        } catch (Exception exception) {
            LOG.error("ImportTask failed", exception);
            return new ImportOperationResultBuilder().setResult(false).setReason(exception.getMessage()).build();
        }
    }

    private void collectFiles() {
        final ListMultimap<LogicalDatastoreType, File> unfiltered = ArrayListMultimap.create(2, 10);
        unfiltered.putAll(Util.collectDataFiles(isBooting));
        for (final LogicalDatastoreType store : unfiltered.asMap().keySet()) {
            final List<File> filtered = unfiltered.asMap()
                    .get(store)
                    .stream()
                    .filter(dataFileFilter)
                    .collect(Collectors.toList());
            dataFiles.putAll(store, filtered);
        }
    }

    private InputStream openModelsFile() throws IOException {
        return Files.newInputStream(Util.getModelsFilePath(isBooting));
    }

    private boolean isDataFilePresent(final LogicalDatastoreType store) {
        return dataFiles.containsKey(store) && !dataFiles.get(store).isEmpty();
    }

    private void importInternal()
            throws IOException, ModelsNotAvailableException, InterruptedException, ExecutionException {
        if (mustValidate) {
            if (Util.isModelFilePresent(isBooting)) {
                try (InputStream is = openModelsFile()) {
                    validateModelAvailability(is);
                }
            } else {
                throw new ModelsNotAvailableException("File with models is not present, validation can't be performed");
            }
        } else {
            LOG.warn("Modules availability check is disabled, import may fail if some of models are missing");
        }
        // Import operational data before config data
        for (final LogicalDatastoreType type : Arrays.asList(LogicalDatastoreType.OPERATIONAL,
                LogicalDatastoreType.CONFIGURATION)) {
            importDatastore(type);
        }
    }

    private void importDatastore(final LogicalDatastoreType type)
            throws  IOException, InterruptedException, ExecutionException {
        final DOMDataTreeReadWriteTransaction rwTrx;
        if (strictDataConsistency) {
            rwTrx = dataBroker.newReadWriteTransaction();
        } else {
            rwTrx = null;
        }
        boolean hasDataFile = isDataFilePresent(type);
        if (DataStoreScope.All.equals(clearScope) || DataStoreScope.Data.equals(clearScope) && hasDataFile) {
            removeChildNodes(type, rwTrx);
        }
        if (!hasDataFile) {
            LOG.info("No data file for datastore {}, import skipped", type.name().toLowerCase());
        } else {
            for (final File f : dataFiles.get(type)) {
                try (InputStream is = new FileInputStream(f)) {
                    LOG.info("Loading data into {} datastore from file {}", type.name().toLowerCase(),
                            f.getAbsolutePath());
                    final NormalizedNodeContainerBuilder<?, ?, ?, ?> builder = ImmutableContainerNodeBuilder.create()
                            .withNodeIdentifier(new YangInstanceIdentifier.NodeIdentifier(
                                    schemaService.getGlobalContext().getQName()));
                    try (NormalizedNodeStreamWriter writer = ImmutableNormalizedNodeStreamWriter.from(builder)) {
                        try (JsonParserStream jsonParser = JsonParserStream
                                .create(writer,CODEC.getShared(schemaService.getGlobalContext()))) {
                            try (JsonReader reader = new JsonReader(new InputStreamReader(is))) {
                                jsonParser.parse(reader);
                                importFromNormalizedNode(rwTrx, type, builder.build());
                            }
                        }
                    }
                }
            }
        }
        if (strictDataConsistency) {
            rwTrx.commit().get();
        }
    }

    private void validateModelAvailability(final InputStream inputStream) throws ModelsNotAvailableException {
        final List<Model> md = Util.parseModels(inputStream);
        final Set<Module> modules = schemaService.getGlobalContext().getModules();
        final Set<Model> missing = Sets.newHashSet();
        for (final Model m : md) {
            LOG.debug("Checking availability of {}", m);
            boolean found = false;
            for (final Module mod : modules) {
                if (mod.getName().equals(m.getModule()) && mod.getNamespace().toString().equals(m.getNamespace())
                        && Objects.equal(mod.getRevision().map(Revision::toString).orElse(null), m.getRevision())) {
                    found = true;
                }
            }
            if (!found) {
                missing.add(m);
            }
        }
        if (!missing.isEmpty()) {
            throw new ModelsNotAvailableException("Following modules are not available : " + missing);
        }
    }

    private void removeChildNodes(final LogicalDatastoreType type, final DOMDataTreeReadWriteTransaction rwTrx)
            throws InterruptedException, ExecutionException {
        final DOMDataTreeReadWriteTransaction removeTrx;
        if (strictDataConsistency) {
            Preconditions.checkNotNull(rwTrx);
            removeTrx = rwTrx;
        } else {
            removeTrx = dataBroker.newReadWriteTransaction();
        }
        for (final DataSchemaNode child : schemaService.getGlobalContext().getChildNodes()) {
            if (isInternalObject(child.getQName())) {
                LOG.debug("Skipping removal of internal dataobject : {}", child.getQName());
                continue;
            }
            final YangInstanceIdentifier nodeIID = YangInstanceIdentifier.of(child.getQName());
            if (removeTrx.read(type, nodeIID).get().isPresent()) {
                LOG.debug("Will delete : {}", child.getQName());
                removeTrx.delete(type, nodeIID);
            } else {
                LOG.trace("Dataobject not present in {} datastore : {}", type.name().toLowerCase(), child.getQName());
            }
        }
        if (!strictDataConsistency) {
            removeTrx.commit().get();
        }
    }

    private boolean isInternalObject(final QName childQName) {
        return childQName.getLocalName().equals(Util.INTERNAL_LOCAL_NAME);
    }

    private void importFromNormalizedNode(final DOMDataTreeReadWriteTransaction rwTrx, final LogicalDatastoreType type,
            final NormalizedNode<?, ?> data) throws InterruptedException, ExecutionException {
        if (data instanceof NormalizedNodeContainer) {
            @SuppressWarnings("unchecked")
            final NormalizedNodeContainer<? extends PathArgument, ? extends PathArgument,
                    ? extends NormalizedNode<PathArgument, ?>> nnContainer
                        = (NormalizedNodeContainer<? extends PathArgument,
                                ? extends PathArgument, ? extends NormalizedNode<PathArgument, ?>>) data;
            final Collection<? extends NormalizedNode<PathArgument, ?>> children = nnContainer
                    .getValue();
            for (NormalizedNode<YangInstanceIdentifier.PathArgument, ?> child : children) {
                if (isInternalObject(child.getIdentifier().getNodeType())) {
                    LOG.debug("Skipping import of internal dataobject : {}", child.getIdentifier());
                    continue;
                }
                LOG.debug("Will import : {}", child.getIdentifier());
                if (strictDataConsistency) {
                    Preconditions.checkNotNull(rwTrx);
                    rwTrx.put(type, YangInstanceIdentifier.create(child.getIdentifier()), child);
                } else {
                    DOMDataTreeReadWriteTransaction childTrx = dataBroker.newReadWriteTransaction();
                    childTrx.put(type, YangInstanceIdentifier.create(child.getIdentifier()), child);
                    childTrx.commit().get();
                }
            }
        } else {
            throw new IllegalStateException("Root node is not instance of NormalizedNodeContainer");
        }
    }
}
