/*
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see http://www.gnu.org/licenses/
 */
package org.apache.solr.client.solrj.embedded;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;

import com.google.common.base.Strings;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrRequest;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.StreamingResponseCallback;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.params.CommonParams;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.common.util.JavaBinCodec;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.core.CoreContainer;
import org.apache.solr.core.NodeConfig;
import org.apache.solr.core.SolrCore;
import org.apache.solr.core.SolrXmlConfig;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.request.SolrRequestHandler;
import org.apache.solr.request.SolrRequestInfo;
import org.apache.solr.response.BinaryResponseWriter;
import org.apache.solr.response.ResultContext;
import org.apache.solr.response.SolrQueryResponse;
import org.apache.solr.servlet.SolrRequestParsers;

import static org.apache.solr.common.params.CommonParams.PATH;

/**
 * SolrClient that connects directly to a CoreContainer.
 *
 * @since solr 1.3
 */
public class EmbeddedSolrServer extends SolrClient {

  protected final CoreContainer coreContainer;
  protected final String coreName;
  private final SolrRequestParsers _parser;

  /**
   * Create an EmbeddedSolrServer using a given solr home directory
   *
   * @param solrHome        the solr home directory
   * @param defaultCoreName the core to route requests to by default
   */
  public EmbeddedSolrServer(Path solrHome, String defaultCoreName) {
    this(load(new CoreContainer(SolrXmlConfig.fromSolrHome(solrHome))), defaultCoreName);
  }

  /**
   * Create an EmbeddedSolrServer using a NodeConfig
   *
   * @param nodeConfig      the configuration
   * @param defaultCoreName the core to route requests to be default
   */
  public EmbeddedSolrServer(NodeConfig nodeConfig, String defaultCoreName) {
    this(load(new CoreContainer(nodeConfig)), defaultCoreName);
  }

  private static CoreContainer load(CoreContainer cc) {
    cc.load();
    return cc;
  }

  /**
   * Create an EmbeddedSolrServer wrapping a particular SolrCore
   */
  public EmbeddedSolrServer(SolrCore core) {
    this(core.getCoreDescriptor().getCoreContainer(), core.getName());
  }

  /**
   * Create an EmbeddedSolrServer wrapping a CoreContainer.
   * <p>
   * Note that EmbeddedSolrServer will shutdown the wrapped CoreContainer when
   * {@link #close()} is called.
   *
   * @param coreContainer the core container
   * @param coreName      the core to route requests to be default
   */
  public EmbeddedSolrServer(CoreContainer coreContainer, String coreName) {
    if (coreContainer == null) {
      throw new NullPointerException("CoreContainer instance required");
    }
    if (Strings.isNullOrEmpty(coreName))
      throw new SolrException(SolrException.ErrorCode.SERVER_ERROR, "Core name cannot be empty");
    this.coreContainer = coreContainer;
    this.coreName = coreName;
    _parser = new SolrRequestParsers(null);
  }

  // TODO-- this implementation sends the response to XML and then parses it.
  // It *should* be able to convert the response directly into a named list.

  @Override
  public NamedList<Object> request(SolrRequest request, String coreName) throws SolrServerException, IOException {

    String path = request.getPath();
    if (path == null || !path.startsWith("/")) {
      path = "/select";
    }

    SolrRequestHandler handler = coreContainer.getRequestHandler(path);
    if (handler != null) {
      try {
        SolrQueryRequest req = _parser.buildRequestFrom(null, request.getParams(), request.getContentStreams());
        SolrQueryResponse resp = new SolrQueryResponse();
        handler.handleRequest(req, resp);
        checkForExceptions(resp);
        return BinaryResponseWriter.getParsedResponse(req, resp);
      } catch (IOException | SolrException iox) {
        throw iox;
      } catch (Exception ex) {
        throw new SolrServerException(ex);
      }
    }

    if (coreName == null)
      coreName = this.coreName;

    // Check for cores action
    SolrQueryRequest req = null;
    try (SolrCore core = coreContainer.getCore(coreName)) {

      if (core == null) {
        throw new SolrException(SolrException.ErrorCode.SERVER_ERROR, "No such core: " + coreName);
      }

      SolrParams params = request.getParams();
      if (params == null) {
        params = new ModifiableSolrParams();
      }

      // Extract the handler from the path or params
      handler = core.getRequestHandler(path);
      if (handler == null) {
        if ("/select".equals(path) || "/select/".equalsIgnoreCase(path)) {
          String qt = params.get(CommonParams.QT);
          handler = core.getRequestHandler(qt);
          if (handler == null) {
            throw new SolrException(SolrException.ErrorCode.BAD_REQUEST, "unknown handler: " + qt);
          }
        }
      }

      if (handler == null) {
        throw new SolrException(SolrException.ErrorCode.BAD_REQUEST, "unknown handler: " + path);
      }

      req = _parser.buildRequestFrom(core, params, request.getContentStreams());
      req.getContext().put(PATH, path);
      req.getContext().put("httpMethod", request.getMethod().toString());
      SolrQueryResponse rsp = new SolrQueryResponse();
      SolrRequestInfo.setRequestInfo(new SolrRequestInfo(req, rsp));

      core.execute(handler, req, rsp);
      checkForExceptions(rsp);

      // Check if this should stream results
      if (request.getStreamingResponseCallback() != null) {
        try {
          final StreamingResponseCallback callback = request.getStreamingResponseCallback();
          BinaryResponseWriter.Resolver resolver =
              new BinaryResponseWriter.Resolver(req, rsp.getReturnFields()) {
                @Override
                public void writeResults(ResultContext ctx, JavaBinCodec codec) throws IOException {
                  // write an empty list...
                  SolrDocumentList docs = new SolrDocumentList();
                  docs.setNumFound(ctx.docs.matches());
                  docs.setStart(ctx.docs.offset());
                  docs.setMaxScore(ctx.docs.maxScore());
                  codec.writeSolrDocumentList(docs);

                  // This will transform
                  writeResultsBody(ctx, codec);
                }
              };


          ByteArrayOutputStream out = new ByteArrayOutputStream();
          new JavaBinCodec(resolver) {

            @Override
            public void writeSolrDocument(SolrDocument doc) {
              callback.streamSolrDocument(doc);
              //super.writeSolrDocument( doc, fields );
            }

            @Override
            public void writeSolrDocumentList(SolrDocumentList docs) throws IOException {
              if (docs.size() > 0) {
                SolrDocumentList tmp = new SolrDocumentList();
                tmp.setMaxScore(docs.getMaxScore());
                tmp.setNumFound(docs.getNumFound());
                tmp.setStart(docs.getStart());
                docs = tmp;
              }
              callback.streamDocListInfo(docs.getNumFound(), docs.getStart(), docs.getMaxScore());
              super.writeSolrDocumentList(docs);
            }

          }.setWritableDocFields(resolver). marshal(rsp.getValues(), out);

          InputStream in = new ByteArrayInputStream(out.toByteArray());
          return (NamedList<Object>) new JavaBinCodec(resolver).unmarshal(in);
        } catch (Exception ex) {
          throw new RuntimeException(ex);
        }
      }

      // Now write it out
      NamedList<Object> normalized = BinaryResponseWriter.getParsedResponse(req, rsp);
      return normalized;
    } catch (IOException | SolrException iox) {
      throw iox;
    } catch (Exception ex) {
      throw new SolrServerException(ex);
    } finally {
      if (req != null) req.close();
      SolrRequestInfo.clearRequestInfo();
    }
  }

  private static void checkForExceptions(SolrQueryResponse rsp) throws Exception {
    if (rsp.getException() != null) {
      if (rsp.getException() instanceof SolrException) {
        throw rsp.getException();
      }
      throw new SolrServerException(rsp.getException());
    }

  }

  /**
   * Shutdown all cores within the EmbeddedSolrServer instance
   */
  @Override
  @Deprecated
  public void shutdown() {
    coreContainer.shutdown();
  }

  @Override
  public void close() throws IOException {
    shutdown();
  }
  
  /**
   * Getter method for the CoreContainer
   *
   * @return the core container
   */
  public CoreContainer getCoreContainer() {
    return coreContainer;
  }
}
