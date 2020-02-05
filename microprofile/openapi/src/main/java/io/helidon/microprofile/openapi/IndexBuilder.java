/*
 * Copyright (c) 2019-2020 Oracle and/or its affiliates. All rights reserved.
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
 *
 */
package io.helidon.microprofile.openapi;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.enterprise.event.Observes;
import javax.enterprise.inject.spi.Extension;
import javax.enterprise.inject.spi.ProcessAnnotatedType;

import org.jboss.jandex.CompositeIndex;
import org.jboss.jandex.Index;
import org.jboss.jandex.IndexReader;
import org.jboss.jandex.IndexView;
import org.jboss.jandex.Indexer;

/**
 * Portable extension to allow construction of a Jandex index (to pass to
 * SmallRye OpenAPI) from CDI if no {@code META-INF/jandex.idx} file exists on
 * the class path.
 */
public class IndexBuilder implements Extension {

    private static final String INDEX_PATH = "META-INF/jandex.idx";

    private static final Logger LOGGER = Logger.getLogger(IndexBuilder.class.getName());

    private final List<URL> indexURLs;

    private final Set<Class<?>> annotatedTypes = new HashSet<>();

    /**
     * Creates a new instance of the index builder.
     *
     * @throws IOException in case of error checking for the Jandex index files
     */
    public IndexBuilder() throws IOException {
        this(INDEX_PATH);
    }

    IndexBuilder(String... indexPaths) throws IOException {
        indexURLs = findIndexFiles(indexPaths);
        if (indexURLs.isEmpty()) {
            LOGGER.log(Level.INFO, () -> String.format(
                    "OpenAPI support could not locate the Jandex index file %s "
                    + "so will build an in-memory index.%n"
                    + "This slows your app start-up and, depending on CDI configuration, "
                    + "might omit some type information needed for a complete OpenAPI document.%n"
                    + "Consider using the Jandex maven plug-in during your build "
                    + "to create the index and add it to your app.",
                    INDEX_PATH));
        }
    }

    /**
     * Records each type that is annotated unless Jandex index(es) were found on
     * the classpath (in which case we do not need to build our own in memory).
     *
     * @param <X> annotated type
     * @param event {@code ProcessAnnotatedType} event
     */
    private <X> void processAnnotatedType(@Observes ProcessAnnotatedType<X> event) {
        if (indexURLs.isEmpty()) {
            Class<?> c = event.getAnnotatedType()
                    .getJavaClass();
            annotatedTypes.add(c);
        }
    }

    /**
     * Reports an {@link IndexView} for the Jandex index that describes
     * annotated classes for endpoints.
     *
     * @return {@code IndexView} describing discovered classes
     * @throws IOException in case of error reading an existing index file or
     * reading class bytecode from the classpath
     */
    public IndexView indexView() throws IOException {
        return !indexURLs.isEmpty() ? existingIndexFileReader() : indexFromHarvestedClasses();
    }

    /**
     * Builds an {@code IndexView} from existing Jandex index file(s) on the classpath.
     *
     * @return IndexView from all index files
     * @throws IOException in case of error attempting to open an index file
     */
    private IndexView existingIndexFileReader() throws IOException {
        List<IndexView> indices = new ArrayList<>();
        for (URL indexURL : indexURLs) {
            try (InputStream indexIS = indexURL.openStream()) {
                LOGGER.log(Level.FINE, "Adding Jandex index at {0}", indexURL.toString());
                indices.add(new IndexReader(indexIS).read());
            } catch (IOException ex) {
                throw new IOException("Attempted to read from previously-located index file "
                        + indexURL + " but the file cannot be found", ex);
            }
        }
        return indices.size() == 1 ? indices.get(0) : CompositeIndex.create(indices);
    }

    private IndexView indexFromHarvestedClasses() throws IOException {
        Indexer indexer = new Indexer();
        for (Class<?> c : annotatedTypes) {
            try (InputStream is = contextClassLoader().getResourceAsStream(resourceNameForClass(c))) {
                indexer.index(is);
            } catch (IOException ex) {
                throw new IOException("Cannot load bytecode from class "
                        + c.getName() + " at " + resourceNameForClass(c)
                        + " for annotation processing", ex);
            }
        }

        LOGGER.log(Level.FINE, "Using internal Jandex index created from CDI bean discovery");
        Index result = indexer.complete();
        dumpIndex(Level.FINER, result);
        return result;
    }

    private List<URL> findIndexFiles(String... indexPaths) throws IOException {
        List<URL> result = new ArrayList<>();
        for (String indexPath : indexPaths) {
            Enumeration<URL> urls = contextClassLoader().getResources(indexPath);
            while (urls.hasMoreElements()) {
                result.add(urls.nextElement());
            }
        }
        return result;
    }

    private static void dumpIndex(Level level, Index index) throws UnsupportedEncodingException {
        if (LOGGER.isLoggable(level)) {
            LOGGER.log(level, "Dump of internal Jandex index:");
            PrintStream oldStdout = System.out;
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (PrintStream newPS = new PrintStream(baos, true, Charset.defaultCharset().name())) {
                System.setOut(newPS);
                index.printAnnotations();
                index.printSubclasses();
                LOGGER.log(level, baos.toString(Charset.defaultCharset().name()));
            } finally {
                System.setOut(oldStdout);
            }
        }
    }

    private static ClassLoader contextClassLoader() {
        return Thread.currentThread().getContextClassLoader();
    }

    private static String resourceNameForClass(Class<?> c) {
        return c.getName().replace('.', '/') + ".class";
    }
}