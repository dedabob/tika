/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.tika.utils;

import org.apache.tika.exception.TikaException;
import org.xml.sax.EntityResolver;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXNotRecognizedException;
import org.xml.sax.SAXNotSupportedException;
import org.xml.sax.XMLReader;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLResolver;
import javax.xml.stream.XMLStreamException;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.TransformerFactoryConfigurationError;
import java.io.IOException;
import java.io.Serializable;
import java.io.StringReader;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Utility functions for reading XML.  If you are doing SAX parsing, make sure
 * to use the {@link org.apache.tika.sax.OfflineContentHandler} to guard against
 * XML External Entity attacks.
 */
public class XMLReaderUtils implements Serializable {

    /**
     * Serial version UID
     */
    private static final long serialVersionUID = 6110455808615143122L;

    private static final Logger LOG = Logger.getLogger(XMLReaderUtils.class.getName());

    /**
     * Parser pool size
     */
    private static int POOL_SIZE = 10;

    private static final ReentrantReadWriteLock READ_WRITE_LOCK = new ReentrantReadWriteLock();

    private static ArrayBlockingQueue<SAXParser> SAX_PARSERS = new ArrayBlockingQueue<>(POOL_SIZE);

    static {
        try {
            setPoolSize(POOL_SIZE);
        } catch (TikaException e) {
            throw new RuntimeException("problem initializing SAXParser pool", e);
        }
    }


    private static final EntityResolver IGNORING_SAX_ENTITY_RESOLVER = new EntityResolver() {
        public InputSource resolveEntity(String publicId, String systemId) throws SAXException, IOException {
            return new InputSource(new StringReader(""));
        }
    };

    private static final XMLResolver IGNORING_STAX_ENTITY_RESOLVER =
            new XMLResolver() {
                @Override
                public Object resolveEntity(String publicID, String systemID, String baseURI, String namespace) throws
                        XMLStreamException {
                    return "";
                }
            };

    /**
     * Returns the XMLReader specified in this parsing context. If a reader
     * is not explicitly specified, then one is created using the specified
     * or the default SAX parser.
     *
     * @return XMLReader
     * @throws TikaException
     * @see #getSAXParser()
     * @since Apache Tika 1.13
     */
    public static XMLReader getXMLReader() throws TikaException {
        XMLReader reader;
        try {
            reader = getSAXParser().getXMLReader();
        } catch (SAXException e) {
            throw new TikaException("Unable to create an XMLReader", e);
        }
        reader.setEntityResolver(IGNORING_SAX_ENTITY_RESOLVER);
        return reader;
    }

    /**
     * Returns the SAX parser specified in this parsing context. If a parser
     * is not explicitly specified, then one is created using the specified
     * or the default SAX parser factory.
     * <p>
     * Make sure to wrap your handler in the {@link org.apache.tika.sax.OfflineContentHandler} to
     * prevent XML External Entity attacks
     * </p>
     *
     * @return SAX parser
     * @throws TikaException if a SAX parser could not be created
     * @see #getSAXParserFactory()
     * @since Apache Tika 0.8
     */
    public static SAXParser getSAXParser() throws TikaException {
        try {
            return getSAXParserFactory().newSAXParser();
        } catch (ParserConfigurationException e) {
            throw new TikaException("Unable to configure a SAX parser", e);
        } catch (SAXException e) {
            throw new TikaException("Unable to create a SAX parser", e);
        }
    }

    /**
     * Returns the SAX parser factory specified in this parsing context.
     * If a factory is not explicitly specified, then a default factory
     * instance is created and returned. The default factory instance is
     * configured to be namespace-aware, not validating, and to use
     * {@link XMLConstants#FEATURE_SECURE_PROCESSING secure XML processing}.
     * <p>
     * Make sure to wrap your handler in the {@link org.apache.tika.sax.OfflineContentHandler} to
     * prevent XML External Entity attacks
     * </p>
     *
     * @return SAX parser factory
     * @since Apache Tika 0.8
     */
    public static SAXParserFactory getSAXParserFactory() {
        SAXParserFactory factory = SAXParserFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setValidating(false);
        try {
            factory.setFeature(
                    XMLConstants.FEATURE_SECURE_PROCESSING, true);
        } catch (ParserConfigurationException e) {
        } catch (SAXNotSupportedException e) {
        } catch (SAXNotRecognizedException e) {
            // TIKA-271: Some XML parsers do not support the
            // secure-processing feature, even though it's required by
            // JAXP in Java 5. Ignoring the exception is fine here, as
            // deployments without this feature are inherently vulnerable
            // to XML denial-of-service attacks.
        }

        return factory;
    }

    /**
     * Returns the DOM builder factory specified in this parsing context.
     * If a factory is not explicitly specified, then a default factory
     * instance is created and returned. The default factory instance is
     * configured to be namespace-aware and to apply reasonable security
     * features.
     *
     * @return DOM parser factory
     * @since Apache Tika 1.13
     */
    public static DocumentBuilderFactory getDocumentBuilderFactory() {
        //borrowed from Apache POI
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setExpandEntityReferences(false);
        factory.setNamespaceAware(true);
        factory.setValidating(false);

        trySetSAXFeature(factory, XMLConstants.FEATURE_SECURE_PROCESSING, true);
        trySetSAXFeature(factory, "http://xml.org/sax/features/external-general-entities", false);
        trySetSAXFeature(factory, "http://xml.org/sax/features/external-parameter-entities", false);
        trySetSAXFeature(factory, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        trySetSAXFeature(factory, "http://apache.org/xml/features/nonvalidating/load-dtd-grammar", false);
        return factory;
    }

    /**
     * Returns the DOM builder specified in this parsing context.
     * If a builder is not explicitly specified, then a builder
     * instance is created and returned. The builder instance is
     * configured to apply an {@link #IGNORING_SAX_ENTITY_RESOLVER},
     * and it sets the ErrorHandler to <code>null</code>.
     *
     * @return DOM Builder
     * @since Apache Tika 1.13
     */
    public static DocumentBuilder getDocumentBuilder() throws TikaException {
        try {
            DocumentBuilderFactory documentBuilderFactory = getDocumentBuilderFactory();
            DocumentBuilder documentBuilder = documentBuilderFactory.newDocumentBuilder();
            documentBuilder.setEntityResolver(IGNORING_SAX_ENTITY_RESOLVER);
            documentBuilder.setErrorHandler(null);
            return documentBuilder;
        } catch (ParserConfigurationException e) {
            throw new TikaException("XML parser not available", e);
        }
    }

    /**
     * Returns the StAX input factory specified in this parsing context.
     * If a factory is not explicitly specified, then a default factory
     * instance is created and returned. The default factory instance is
     * configured to be namespace-aware and to apply reasonable security
     * using the {@link #IGNORING_STAX_ENTITY_RESOLVER}.
     *
     * @return StAX input factory
     * @since Apache Tika 1.13
     */
    public static XMLInputFactory getXMLInputFactory() {
        XMLInputFactory factory = XMLInputFactory.newFactory();

        tryToSetStaxProperty(factory, XMLInputFactory.IS_NAMESPACE_AWARE, true);
        tryToSetStaxProperty(factory, XMLInputFactory.IS_VALIDATING, false);

        factory.setXMLResolver(IGNORING_STAX_ENTITY_RESOLVER);
        return factory;
    }

    private static void trySetSAXFeature(DocumentBuilderFactory documentBuilderFactory, String feature, boolean enabled) {
        try {
            documentBuilderFactory.setFeature(feature, enabled);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "SAX Feature unsupported: " + feature, e);
        } catch (AbstractMethodError ame) {
            LOG.log(Level.WARNING, "Cannot set SAX feature because outdated XML parser in classpath: " + feature, ame);
        }
    }

    private static void tryToSetStaxProperty(XMLInputFactory factory, String key, boolean value) {
        try {
            factory.setProperty(key, value);
        } catch (IllegalArgumentException e) {
            //swallow
        }
    }

    /**
     * Returns a new transformer
     * <p>
     * The transformer instance is configured to to use
     * {@link XMLConstants#FEATURE_SECURE_PROCESSING secure XML processing}.
     *
     * @return Transformer
     * @throws TikaException when the transformer can not be created
     * @since Apache Tika 1.17
     */
    public static Transformer getTransformer() throws TikaException {
        try {
            TransformerFactory transformerFactory = TransformerFactory.newInstance();
            transformerFactory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            return transformerFactory.newTransformer();
        } catch (TransformerConfigurationException | TransformerFactoryConfigurationError e) {
            throw new TikaException("Transformer not available", e);
        }
    }

    /**
     * Acquire a SAXParser from the pool; create one if it
     * doesn't exist.  Make sure to {@link #releaseParser(SAXParser)} in
     * a <code>finally</code> block every time you call this.
     *
     * @return a SAXParser
     * @throws TikaException
     */
    public static SAXParser acquireSAXParser()
            throws TikaException {
        while (true) {
            SAXParser parser = null;
            try {
                READ_WRITE_LOCK.readLock().lock();
                parser = SAX_PARSERS.poll(10, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                throw new TikaException("interrupted while waiting for SAXParser", e);
            } finally {
                READ_WRITE_LOCK.readLock().unlock();

            }
            if (parser != null) {
                return parser;
            }
        }
    }

    /**
     * Return parser to the pool for reuse
     *
     * @param parser parser to return
     */
    public static void releaseParser(SAXParser parser) {
        try {
            parser.reset();
        } catch (UnsupportedOperationException e) {
            //ignore
        }
        try {
            READ_WRITE_LOCK.readLock().lock();
            //if there are extra parsers (e.g. after a reset of the pool to a smaller size),
            // this parser will not be added and will then be gc'd
            boolean success = SAX_PARSERS.offer(parser);
        } finally {
            READ_WRITE_LOCK.readLock().unlock();
        }
    }

    /**
     * Set the pool size for cached XML parsers.
     *
     * @param poolSize
     */
    public static void setPoolSize(int poolSize) throws TikaException {
        try {
            //stop the world with a write lock.
            //parsers that are currently in use will be offered, but not
            //accepted and will be gc'd
            READ_WRITE_LOCK.writeLock().lock();
            if (SAX_PARSERS.size() == poolSize) {
                return;
            }
            SAX_PARSERS = new ArrayBlockingQueue<>(poolSize);
            for (int i = 0; i < poolSize; i++) {
                SAX_PARSERS.offer(getSAXParser());
            }
            POOL_SIZE = poolSize;
        } finally {
            READ_WRITE_LOCK.writeLock().unlock();
        }
    }
}
