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
package org.apache.jackrabbit.oak.plugins.index.elastic.index;

import co.elastic.clients.elasticsearch._types.analysis.Analyzer;
import co.elastic.clients.elasticsearch._types.analysis.CharFilterDefinition;
import co.elastic.clients.elasticsearch._types.analysis.CustomAnalyzer;
import co.elastic.clients.elasticsearch._types.analysis.TokenFilterDefinition;
import co.elastic.clients.elasticsearch._types.analysis.TokenizerDefinition;
import co.elastic.clients.elasticsearch.indices.IndexSettingsAnalysis;
import co.elastic.clients.json.JsonData;
import org.apache.commons.io.IOUtils;
import org.apache.jackrabbit.JcrConstants;
import org.apache.jackrabbit.oak.api.Blob;
import org.apache.jackrabbit.oak.api.PropertyState;
import org.apache.jackrabbit.oak.api.Type;
import org.apache.jackrabbit.oak.plugins.index.search.FulltextIndexConstants;
import org.apache.jackrabbit.oak.plugins.index.search.util.ConfigUtil;
import org.apache.jackrabbit.oak.spi.state.ChildNodeEntry;
import org.apache.jackrabbit.oak.spi.state.NodeState;
import org.apache.jackrabbit.oak.spi.state.NodeStateUtils;
import org.apache.lucene.analysis.en.AbstractWordsFileFilterFactory;
import org.apache.lucene.analysis.util.AbstractAnalysisFactory;
import org.apache.lucene.analysis.util.CharFilterFactory;
import org.apache.lucene.analysis.util.TokenFilterFactory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/**
 * Loads custom analysis index settings from a JCR NodeState. It also takes care of required transformations from lucene
 * to elasticsearch configuration options.
 */
public class ElasticCustomAnalyzer {

    private static final String ANALYZER_TYPE = "type";

    private static final Set<String> IGNORE_PROP_NAMES = new HashSet<>(
            Arrays.asList(FulltextIndexConstants.ANL_CLASS, FulltextIndexConstants.ANL_NAME, JcrConstants.JCR_PRIMARYTYPE));

    private static final Map<Class<? extends TokenFilterFactory>, Map<String, String>> CONFIGURATION_MAPPING;

    static {
        CONFIGURATION_MAPPING = new LinkedHashMap<>();
        CONFIGURATION_MAPPING.put(AbstractWordsFileFilterFactory.class, Collections.singletonMap("words", "stopwords"));
    }

    @Nullable
    public static IndexSettingsAnalysis.Builder buildCustomAnalyzers(NodeState state, String analyzerName) {
        if (state != null) {
            NodeState defaultAnalyzer = state.getChildNode(FulltextIndexConstants.ANL_DEFAULT);
            if (defaultAnalyzer.exists()) {
                IndexSettingsAnalysis.Builder builder = new IndexSettingsAnalysis.Builder();
                Map<String, Object> analyzer = new HashMap<>();
                String builtIn = defaultAnalyzer.getString(FulltextIndexConstants.ANL_CLASS);
                if (builtIn == null) {
                    builtIn = defaultAnalyzer.getString(FulltextIndexConstants.ANL_NAME);
                }
                if (builtIn != null) {
                    analyzer.put(ANALYZER_TYPE, normalize(builtIn));

                    // additional builtin params
                    for (PropertyState ps : defaultAnalyzer.getProperties()) {
                        if (!IGNORE_PROP_NAMES.contains(ps.getName())) {
                            analyzer.put(normalize(ps.getName()), ps.getValue(Type.STRING));
                        }
                    }

                    // content params, usually stop words
                    for (ChildNodeEntry nodeEntry : defaultAnalyzer.getChildNodeEntries()) {
                        try {
                            analyzer.put(normalize(nodeEntry.getName()), loadContent(nodeEntry.getNodeState(), nodeEntry.getName()));
                        } catch (IOException e) {
                            throw new IllegalStateException("Unable to load content for node entry " + nodeEntry.getName(), e);
                        }
                    }

                    builder.analyzer(analyzerName, new Analyzer(null, JsonData.of(analyzer)));
                } else { // try to compose the analyzer
                    builder.tokenizer("custom_tokenizer", tb -> tb.definition(loadTokenizer(defaultAnalyzer.getChildNode(FulltextIndexConstants.ANL_TOKENIZER))));

                    LinkedHashMap<String, TokenFilterDefinition> tokenFilters = loadFilters(
                            defaultAnalyzer.getChildNode(FulltextIndexConstants.ANL_FILTERS),
                            TokenFilterFactory::lookupClass, TokenFilterDefinition::new
                    );
                    tokenFilters.forEach((key, value) -> builder.filter(key, fn -> fn.definition(value)));

                    LinkedHashMap<String, CharFilterDefinition> charFilters = loadFilters(
                            defaultAnalyzer.getChildNode(FulltextIndexConstants.ANL_CHAR_FILTERS),
                            CharFilterFactory::lookupClass, CharFilterDefinition::new
                    );
                    charFilters.forEach((key, value) -> builder.charFilter(key, fn -> fn.definition(value)));

                    builder.analyzer(analyzerName, bf -> bf.custom(CustomAnalyzer.of(cab ->
                            cab.tokenizer("custom_tokenizer")
                                    .filter(new ArrayList<>(tokenFilters.keySet()))
                                    .charFilter(new ArrayList<>(charFilters.keySet()))
                    )));
                }
                return builder;
            }
        }
        return null;
    }

    @NotNull
    private static TokenizerDefinition loadTokenizer(NodeState state) {
        String name = normalize(Objects.requireNonNull(state.getString(FulltextIndexConstants.ANL_NAME)));
        Map<String, Object> args = convertNodeState(state);
        args.put(ANALYZER_TYPE, name);
        return new TokenizerDefinition(name, JsonData.of(args));
    }

    private static <FD> LinkedHashMap<String, FD> loadFilters(NodeState state,
                                                              Function<String, Class<? extends AbstractAnalysisFactory>> lookup,
                                                              BiFunction<String, JsonData, FD> factory) {
        LinkedHashMap<String, FD> filters = new LinkedHashMap<>();
        int i = 0;
        for (ChildNodeEntry entry : state.getChildNodeEntries()) {
            String name = normalize(entry.getName());
            Class<? extends AbstractAnalysisFactory> tff = lookup.apply(name);
            Optional<Map<String, String>> mapping =
                    CONFIGURATION_MAPPING.entrySet().stream().filter(k -> k.getKey().isAssignableFrom(tff)).map(Map.Entry::getValue).findFirst();
            Map<String, Object> args = convertNodeState(entry.getNodeState(), mapping.orElseGet(Collections::emptyMap));
            args.put(ANALYZER_TYPE, name);

            filters.put(name + "_" + i++, factory.apply(name, JsonData.of(args)));
        }
        return filters;
    }

    private static LinkedHashMap<String, TokenFilterDefinition> loadTokenFilters(NodeState state) {
        LinkedHashMap<String, TokenFilterDefinition> filters = new LinkedHashMap<>();
        int i = 0;
        for (ChildNodeEntry entry : state.getChildNodeEntries()) {
            String name = normalize(entry.getName());
            Class<? extends TokenFilterFactory> tff = TokenFilterFactory.lookupClass(name);
            Optional<Map<String, String>> mapping =
                    CONFIGURATION_MAPPING.entrySet().stream().filter(k -> k.getKey().isAssignableFrom(tff)).map(Map.Entry::getValue).findFirst();
            Map<String, Object> args = convertNodeState(entry.getNodeState(), mapping.orElseGet(Collections::emptyMap));
            args.put(ANALYZER_TYPE, name);

            filters.put("oak_token_filter_" + i++, new TokenFilterDefinition(name, JsonData.of(args)));
        }
        return filters;
    }

    private static LinkedHashMap<String, CharFilterDefinition> loadCharFilters(NodeState state) {
        LinkedHashMap<String, CharFilterDefinition> filters = new LinkedHashMap<>();
        int i = 0;
        for (ChildNodeEntry entry : state.getChildNodeEntries()) {
            String name = normalize(entry.getName());
            Class<? extends CharFilterFactory> cff = CharFilterFactory.lookupClass(name);
            Optional<Map<String, String>> mapping =
                    CONFIGURATION_MAPPING.entrySet().stream().filter(k -> k.getKey().isAssignableFrom(cff)).map(Map.Entry::getValue).findFirst();
            Map<String, Object> args = convertNodeState(entry.getNodeState(), mapping.orElseGet(Collections::emptyMap));
            args.put(ANALYZER_TYPE, name);

            filters.put("oak_char_filter_" + i++, new CharFilterDefinition(name, JsonData.of(args)));
        }
        return filters;
    }

    private static List<String> loadContent(NodeState file, String name) throws IOException {
        List<String> result = new ArrayList<>();
        Blob blob = ConfigUtil.getBlob(file, name);
        Reader content = null;
        try {
            content = new InputStreamReader(blob.getNewStream(), StandardCharsets.UTF_8);
            BufferedReader br = null;
            try {
                br = new BufferedReader(content);
                String word;
                while ((word = br.readLine()) != null) {
                    result.add(word.trim());
                }
            } finally {
                IOUtils.close(br);
            }
            return result;
        } finally {
            IOUtils.close(content);
        }
    }

    /**
     * Normalizes one of the following values:
     * - lucene class (eg: org.apache.lucene.analysis.en.EnglishAnalyzer)
     * - lucene name (eg: Standard)
     * into the elasticsearch compatible value
     */
    private static String normalize(String value) {
        String[] anlClassTokens = value.split("\\.");
        String name = anlClassTokens[anlClassTokens.length - 1];
        return name.toLowerCase().replace("analyzer", "");
    }

    private static Map<String, Object> convertNodeState(NodeState state) {
        return convertNodeState(state, Collections.emptyMap());
    }

    private static Map<String, Object> convertNodeState(NodeState state, Map<String, String> mapping) {
        return StreamSupport.stream(Spliterators.spliteratorUnknownSize(state.getProperties().iterator(), Spliterator.ORDERED), false)
                .filter(ps -> ps.getType() != Type.BINARY)
                .filter(ps -> !ps.isArray())
                .filter(ps -> !NodeStateUtils.isHidden(ps.getName()))
                .filter(ps -> !IGNORE_PROP_NAMES.contains(ps.getName()))
                .collect(Collectors.toMap(ps -> {
                    String remappedName = mapping.get(ps.getName());
                    return remappedName != null ? remappedName : ps.getName();
                }, ps -> {
                    String value = ps.getValue(Type.STRING);
                    List<String> values = Arrays.asList(value.split(","));
                    if (values.stream().allMatch(v -> state.hasChildNode(v.trim()))) {
                        return values.stream().flatMap(v -> {
                            try {
                                return loadContent(state.getChildNode(v.trim()), v.trim()).stream();
                            } catch (IOException e) {
                                throw new IllegalStateException(e);
                            }
                        }).collect(Collectors.toList());
                    } else return value;
                }));
    }
}
