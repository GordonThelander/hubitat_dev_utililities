#!/usr/bin/env groovy

import groovy.json.JsonSlurper

File root = new File('.').canonicalFile
File decoderFile = new File(root, 'vrb_graph_decoder_reference.groovy')
if (!decoderFile.isFile()) {
    throw new IllegalStateException('Run this verifier from the Visual Rule Builder Storage directory.')
}

def decoder = new GroovyShell().parse(decoderFile)
JsonSlurper json = new JsonSlurper()
Map labelsEnvelope = json.parse(new File(root, 'fixtures/device_labels.json')) as Map
Map labels = (labelsEnvelope.devices ?: [:]) as Map

['simple', 'complex'].each { String fixtureName ->
    Map graph = json.parse(new File(root, "fixtures/${fixtureName}_graph.json")) as Map
    List expected = json.parse(new File(root, "fixtures/${fixtureName}_expected_steps.json")) as List
    Map actual = decoder.decodeVisualRuleBuilderGraph(graph, labels) as Map

    assert actual.warnings == [] : "${fixtureName}: unexpected warnings: ${actual.warnings}"
    assert actual.unknownNodeTypes == [] : "${fixtureName}: unexpected node types: ${actual.unknownNodeTypes}"
    assert actual.steps == expected : "${fixtureName}: decoded steps differ from expected output.\nActual: ${actual.steps}\nExpected: ${expected}"
    println "PASS: ${fixtureName} fixture (${actual.steps.size()} decoded steps)"
}

println 'All Visual Rule Builder fixtures passed.'

