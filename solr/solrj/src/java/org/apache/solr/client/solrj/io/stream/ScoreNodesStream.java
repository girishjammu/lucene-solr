package org.apache.solr.client.solrj.io.stream;

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

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.HashMap;

import org.apache.solr.client.solrj.impl.CloudSolrClient;
import org.apache.solr.client.solrj.io.SolrClientCache;
import org.apache.solr.client.solrj.io.Tuple;
import org.apache.solr.client.solrj.io.comp.StreamComparator;
import org.apache.solr.client.solrj.io.stream.expr.Explanation;
import org.apache.solr.client.solrj.io.stream.expr.Explanation.ExpressionType;
import org.apache.solr.client.solrj.io.stream.expr.Expressible;
import org.apache.solr.client.solrj.io.stream.expr.StreamExplanation;
import org.apache.solr.client.solrj.io.stream.expr.StreamExpression;
import org.apache.solr.client.solrj.io.stream.expr.StreamExpressionNamedParameter;
import org.apache.solr.client.solrj.io.stream.expr.StreamExpressionValue;
import org.apache.solr.client.solrj.io.stream.expr.StreamFactory;
import org.apache.solr.client.solrj.request.QueryRequest;
import org.apache.solr.common.params.CommonParams;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.apache.solr.common.params.TermsParams;
import org.apache.solr.common.util.NamedList;

/**
 *  Iterates over a gatherNodes() expression and scores the node Tuples based based on tf-idf.
 *
 *  Expression Syntax:
 *
 *  Default function call uses the "count(*)" value for node freq.
 *
 *  You can use a different value for node freq by providing the nodeFreq param
 *  scoreNodes(gatherNodes(...), nodeFreq="min(weight)")
 *
 **/

public class ScoreNodesStream extends TupleStream implements Expressible
{

  private static final long serialVersionUID = 1;

  protected String zkHost;
  private TupleStream stream;
  private transient SolrClientCache clientCache;
  private Map<String, Tuple> nodes = new HashMap();
  private Iterator<Tuple> tuples;
  private String termFreq;

  public ScoreNodesStream(TupleStream tupleStream, String nodeFreqField) throws IOException {
    init(tupleStream, nodeFreqField);
  }

  public ScoreNodesStream(StreamExpression expression, StreamFactory factory) throws IOException {
    // grab all parameters out
    List<StreamExpression> streamExpressions = factory.getExpressionOperandsRepresentingTypes(expression, Expressible.class, TupleStream.class);
    StreamExpressionNamedParameter nodeFreqParam = factory.getNamedOperand(expression, "termFreq");

    String docFreqField = "count(*)";
    if(nodeFreqParam != null) {
      docFreqField = nodeFreqParam.getParameter().toString();
    }

    if(1 != streamExpressions.size()){
      throw new IOException(String.format(Locale.ROOT,"Invalid expression %s - expecting a single stream but found %d",expression, streamExpressions.size()));
    }

    zkHost = factory.getDefaultZkHost();

    if(null == zkHost){
      throw new IOException("zkHost not found");
    }

    TupleStream stream = factory.constructStream(streamExpressions.get(0));

    init(stream, docFreqField);
  }

  private void init(TupleStream tupleStream, String termFreq) throws IOException{
    this.stream = tupleStream;
    this.termFreq = termFreq;
  }

  @Override
  public StreamExpression toExpression(StreamFactory factory) throws IOException{
    return toExpression(factory, true);
  }

  private StreamExpression toExpression(StreamFactory factory, boolean includeStreams) throws IOException {
    // function name
    StreamExpression expression = new StreamExpression(factory.getFunctionName(this.getClass()));

    // nodeFreqField
    expression.addParameter(new StreamExpressionNamedParameter("termFreq", termFreq));

    if(includeStreams){
      // stream
      if(stream instanceof Expressible){
        expression.addParameter(((Expressible)stream).toExpression(factory));
      }
      else{
        throw new IOException("This ScoreNodesStream contains a non-expressible TupleStream - it cannot be converted to an expression");
      }
    }
    else{
      expression.addParameter("<stream>");
    }

    return expression;
  }

  @Override
  public Explanation toExplanation(StreamFactory factory) throws IOException {

    return new StreamExplanation(getStreamNodeId().toString())
        .withChildren(new Explanation[]{
            stream.toExplanation(factory)
        })
        .withFunctionName(factory.getFunctionName(this.getClass()))
        .withImplementingClass(this.getClass().getName())
        .withExpressionType(ExpressionType.STREAM_DECORATOR)
        .withExpression(toExpression(factory, false).toString());
  }

  public void setStreamContext(StreamContext context) {
    this.clientCache = context.getSolrClientCache();
    this.stream.setStreamContext(context);
  }

  public List<TupleStream> children() {
    List<TupleStream> l =  new ArrayList();
    l.add(stream);
    return l;
  }

  public void open() throws IOException {
    stream.open();
    Tuple node = null;
    StringBuilder builder = new StringBuilder();
    String field = null;
    String collection = null;
    while(true) {
      node = stream.read();
      if(node.EOF) {
        break;
      }

      String nodeId = node.getString("node");
      nodes.put(nodeId, node);
      if(builder.length() > 0) {
        builder.append(",");
        field = node.getString("field");
        collection = node.getString("collection");
      }
      builder.append(nodeId);
    }

    CloudSolrClient client = clientCache.getCloudSolrClient(zkHost);
    ModifiableSolrParams params = new ModifiableSolrParams();
    params.add(CommonParams.QT, "/terms");
    params.add(TermsParams.TERMS, "true");
    params.add(TermsParams.TERMS_FIELD, field);
    params.add(TermsParams.TERMS_STATS, "true");
    params.add(TermsParams.TERMS_LIST, builder.toString());
    QueryRequest request = new QueryRequest(params);


    try {

      //Get the response from the terms component
      NamedList response = client.request(request, collection);
      NamedList<Number> stats = (NamedList<Number>)response.get("stats");
      long numDocs = stats.get("numDocs").longValue();
      NamedList<NamedList<Number>> fields = (NamedList<NamedList<Number>>)response.get("terms");

      int size = fields.size();
      for(int i=0; i<size; i++) {
        String fieldName = fields.getName(i);
        NamedList<Number> terms = fields.get(fieldName);
        int tsize = terms.size();
        for(int t=0; t<tsize; t++) {
          String term = terms.getName(t);
          Number docFreq = terms.get(term);
          Tuple tuple = nodes.get(term);
          Number termFreqValue = (Number)tuple.get(termFreq);
          float score = termFreqValue.floatValue() * (float) (Math.log((numDocs + 1) / (docFreq.doubleValue() + 1)) + 1.0);
          tuple.put("nodeScore", score);
          tuple.put("docFreq", docFreq);
          tuple.put("numDocs", numDocs);
        }
      }
    } catch (Exception e) {
      throw new IOException(e);
    }

    tuples = nodes.values().iterator();
  }

  public void close() throws IOException {
    stream.close();
  }

  public StreamComparator getComparator(){
    return null;
  }

  public Tuple read() throws IOException {
    if(tuples.hasNext()) {
      return tuples.next();
    } else {
      Map map = new HashMap();
      map.put("EOF", true);
      return new Tuple(map);
    }
  }

  public StreamComparator getStreamSort(){
    return null;
  }

  public int getCost() {
    return 0;
  }

}