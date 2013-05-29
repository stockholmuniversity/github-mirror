#!/usr/bin/env groovy
/*
 * Copyright (c) 2013, IT Services, Stockholm University
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * Redistributions of source code must retain the above copyright notice, this
 * list of conditions and the following disclaimer.
 *
 * Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation
 * and/or other materials provided with the distribution.
 *
 * Neither the name of Stockholm University nor the names of its contributors
 * may be used to endorse or promote products derived from this software
 * without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */

/**
 * A groovy script to update github mirrors with an embedded web server to receive webhooks.
 *
 * @author <a href="mailto:bjorn.westlin@su.se">Björn Westlin</a>
 * @author <a href="mailto:lucien.bokouka@su.se">Lucien Bokouka</a>
 */

import groovy.json.JsonSlurper
import org.apache.commons.net.util.SubnetUtils
import org.jboss.netty.handler.codec.http.QueryStringDecoder
import org.vertx.groovy.core.Vertx
import org.vertx.groovy.core.buffer.Buffer
import org.vertx.groovy.core.http.HttpServerRequest

@Grapes([
  @Grab(group = 'org.vert-x', module = 'vertx-lang-groovy', version = '1.3.1.final'),
  @Grab(group = 'commons-net', module = 'commons-net', version = '3.2')
])


def startServer(int port, configClosure) {
  def vertx = Vertx.newVertx("localhost")
  def eventBus = vertx.eventBus

  vertx.createHttpServer().requestHandler { HttpServerRequest request ->
    def config = configClosure()

    String remoteIp = request.toJavaRequest().conn.channel.remoteAddress.hostString
    if (!isRemoteIpAllowed(remoteIp, config.ipAddressRestrictions)) {
      println "Http-request from ${remoteIp} is not allowed!"
      request.response.close()
      return
    }

    def body = new Buffer()
    request.dataHandler { buffer -> body << buffer }

    request.endHandler {

      QueryStringDecoder qsd = new QueryStringDecoder(body.toString(), false);
      Map postParams = qsd.getParameters();

      request.response.putHeader("Content-Type", "text/plain")
      request.response.end("Ok, thanks!")

      String payload = postParams?.payload?.get(0)
      def json = new JsonSlurper().parseText(payload)

      println "Webhook request recieved for github repo: ${json?.repository?.name} with url: ${json?.repository?.url}"
      List mirrorNames = getMirrorNamesForGithubURL(json?.repository?.url, config)
      if (mirrorNames) {
        eventBus.publish("mirrors.update", mirrorNames.join(","))
      }
      else {
        println "No mirror is configured for github repo: ${json?.repository?.name} with url: ${json?.repository?.url}"
      }
    }

  }.listen(port)

  eventBus.registerHandler("mirrors.update") { message ->
    String body = message.body
    mirrorGithubRepositorys(body.split(",") as List, configClosure())
  }

}

def isRemoteIpAllowed(String remoteIp, List ipAddressRestrictions) {
  if (!ipAddressRestrictions) return true

  boolean isAllowed = false

  for (restriction in ipAddressRestrictions) {
    def subnet = (new SubnetUtils(restriction)).getInfo()
    if (subnet.isInRange(remoteIp)) {
      isAllowed = true
      break
    }
  }
  isAllowed
}

def getMirrorNamesForGithubURL(String githubURL, config) {
  def (user, repo) = githubURL.split("/").getAt([-2, -1])

  config.mirrors.findAll { mirror ->
    mirror.url ==~ ".*[/:]${user}/${repo}.git\$"
  }.collect { mirror ->
    mirror.name
  }
}

def mirrorGithubRepositorys(List mirrorNames, config) {

  File baseMirrorDir = new File(config.baseMirrorDir)
  if (!baseMirrorDir.exists())
    throw new IllegalArgumentException("The baseMirrorDir directory ${config.baseMirrorDir} does not exists!")

  List mirrorsToUpdate = mirrorNames ? config.mirrors.findAll { mirrorNames.contains(it.name) } : config.mirrors

  for (mirror in mirrorsToUpdate) {
    String mirrorDirName = mirror.name + ".git"
    File mirrorDir = new File(baseMirrorDir, mirrorDirName)

    if (!mirrorDir.exists() || mirrorDir.list()?.length == 0) {
      println "Creating new github mirror at ${mirrorDir.absolutePath}/${mirrorDirName} for url ${mirror.url}"
      executeGitCommand("clone --mirror ${mirror.url} ${mirrorDirName}", baseMirrorDir, mirror.wrapper ?: "")
    }
    else {
      println "Updating github mirror at ${mirrorDir.absolutePath} from url ${mirror.url}"
      executeGitCommand("fetch -q", mirrorDir, mirror.wrapper ?: "")
    }
  }
}

def executeGitCommand(String command, File cwd, String wrapper) {
  String gitCommand = "${wrapper?:''} git ${command}".trim()
  println "Executing git command: \"${gitCommand}\" in directory: \"${cwd}\""
  def proc = gitCommand.execute([], cwd)
  proc.waitForOrKill(10 * 60 * 1000) // Wait a maximum of 10 minutes

  println "Git exited with code: ${proc.exitValue()}"
  String stderr = proc.err.text
  if (stderr) println "  stderr:\n${stderr.split("\n").collect{ "    " + it }.join("\n")}"
  String stdout = proc.in.text
  if (stdout) println "  stdout:\n${stdout.split("\n").collect{ "    " + it }.join("\n")}"
}

def handleArgs(String[] args) {
  def cli = new CliBuilder(usage: 'githubmirror.groovy [options] [repository name]', header: 'Options:')
  cli.with {
    h longOpt: 'help', 'Show usage information'
    c longOpt: 'config', args: 1, argName: 'file', 'Config file (.json)'
    s longOpt: 'server', 'Start webserver for handling post-receive-hooks'
    p longOpt: 'port', args: 1, argName: 'portnum', 'Port for webserver to listen to'
  }

  def options = cli.parse(args)
  if (!options.c || options.h) {
    if (!options.c) println "Error! No config file specified."
    cli.usage()
    return
  }

  def config = { ->
    new JsonSlurper().parse(new FileReader(options.c))
  }

  if (options.s) {
    startServer(options.p ? options.p as int : 8080, config)
  }
  else {
    mirrorGithubRepositorys(options.arguments() ?: [], config())
  }

}

handleArgs(args)
