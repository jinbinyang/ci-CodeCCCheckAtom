package com.tencent.devops.docker.scm

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.tencent.bk.devops.plugin.utils.JsonUtil
import com.tencent.devops.docker.Build
import com.tencent.devops.docker.ScanComposer
import com.tencent.devops.docker.pojo.CommandParam
import com.tencent.devops.docker.pojo.ImageParam
import com.tencent.devops.docker.scm.pojo.ScmInfoItem
import com.tencent.devops.docker.scm.pojo.ScmInfoJson
import com.tencent.devops.docker.tools.LogUtils
import com.tencent.devops.docker.utils.CodeccWeb
import com.tencent.devops.docker.utils.CommonUtils
import com.tencent.devops.pojo.OSType
import com.tencent.devops.scm.GitInfo
import com.tencent.devops.scm.SvnInfo
import com.tencent.devops.scm.Utils
import com.tencent.devops.scm.pojo.ScmInfoVO
import com.tencent.devops.utils.CodeccEnvHelper
import com.tencent.devops.utils.CodeccParamsHelper
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File
import java.net.URL
import java.util.*

class ScmInfoNew(
        override val commandParam: CommandParam,
        override val toolName: String,
        override val streamName: String,
        override val taskId: Long
) : Scm(commandParam, toolName, streamName, taskId) {

    private fun appendOutputFile(outputFile: String) {
        val outPutFileText = File(outputFile).readText()
//        val scmInfoJson = jacksonObjectMapper().readValue<ScmInfoJson>(outPutFileText)
        val outputFileObj = jacksonObjectMapper().readValue<List<ScmInfoItem>>(outPutFileText)
        val scmInfoList = mutableListOf<ScmInfoItem>()
        outputFileObj.forEach { scmInfoItem ->
            if (Objects.nonNull(commandParam.repos)) {
                commandParam.repos.forEach { repo ->
                    var url = repo.url
                    if (url.endsWith("/")) {
                        url = url.substring(0, url.lastIndex)
                    }
                    if (repo.type.equals("SVN") && repo.relativePath.isNotEmpty()) {
                        if (url.startsWith("svn+ssh")){
                            if (repo.relativePath.startsWith("/")){
                                url = repo.url + repo.relativePath
                            }else{
                                url = repo.url + "/" + repo.relativePath
                            }
                        }else {
                            var builder = url.toHttpUrlOrNull()!!.newBuilder()
                            repo.relativePath.split("/").forEach { it ->
                                builder = builder.addPathSegment(it)
                            }
                            url = builder.toString()
                        }
                    }
                    LogUtils.printDebugLog("repo url ${url}")
                    LogUtils.printDebugLog("scm info url ${scmInfoItem.url}")
                    if (url.equals(scmInfoItem.url)){
                        LogUtils.printDebugLog("repoId: ${repo.repoHashId}")
                        scmInfoItem.repoId = repo.repoHashId
                        return@forEach
                    }
                }
            }
            scmInfoItem.taskId = taskId.toString()
            scmInfoItem.buildId = commandParam.landunParam.buildId

            //如果build中的版本字段为空，则赋值
            if(Build.codeRepoRevision.isNullOrBlank() && !scmInfoItem.commitID.isNullOrBlank()){
                LogUtils.printLog("need to set value to revision")
                Build.codeRepoRevision = scmInfoItem.commitID
            }
            scmInfoList.add(scmInfoItem)
        }
        val integrateOutPutFileText = jacksonObjectMapper().writeValueAsString(scmInfoList)
        File(outputFile).writeText(integrateOutPutFileText)
    }

    private fun getUrl(url: String): URL {
        return if (url.startsWith("http://") || url.startsWith("https://")) {
            URL(url)
        } else {
            val sshUrlArr = url.split(":")
            URL("http://" + sshUrlArr.first().removePrefix("git@") + "/" + sshUrlArr.last())
        }
    }

    override fun localRunCmd(inputFile: String, outputFile: String){
        var scmInfoList = mutableListOf<ScmInfoVO>()
        var inputFileList = mutableListOf<String>()
        var scmInfoVO = ScmInfoVO()
        val inputFileInfo = jacksonObjectMapper().readValue<Map<String, Any?>>(File(inputFile).readText())
        if (inputFileInfo["dir_path_list"] != null) {
            inputFileList = inputFileInfo["dir_path_list"] as MutableList<String>
        }
        inputFileList.forEach { dirPath ->
            when (Utils.checkScmDirPath(dirPath)) {
                "git" -> {
                    scmInfoVO = GitInfo.infoRun(dirPath)
                    scmInfoVO = GitInfo.getSubmodule(dirPath, scmInfoVO)
                }
                "svn" -> {
                    scmInfoVO = SvnInfo.infoRun(dirPath)
                }
                "perforce" -> {
                    scmInfoVO = getP4Client().getPerforceInfo()
                }
                else -> {
                    LogUtils.printLog("scmType is empty")
                }
            }
            if (!scmInfoList.equals(ScmInfoVO())){
                scmInfoList.add(scmInfoVO)
                scmInfoVO = ScmInfoVO()
            }
        }

        val scmBlameOutputFile = File(outputFile)
        scmBlameOutputFile.bufferedWriter().use { out -> out.write(JsonUtil.toJson(scmInfoList)) }
    }

    override fun generateCmd(inputFile: String, outputFile: String): List<String> {
        val cmdList = mutableListOf<String>()
        if (commandParam.scmType == "git" || commandParam.scmType == "github") {
            cmdList.add("python3 /usr/codecc/scm_tools/src/git_info.py --input=$inputFile --output=$outputFile")
        } else if (commandParam.scmType == "svn") {
            cmdList.add("python3 /usr/codecc/scm_tools/src/svn_info.py --input=$inputFile --output=$outputFile")
        } else {
            LogUtils.printLog("scmType is empty")
            cmdList.clear()
        }
        return cmdList
    }

    override fun scmOpFail(inputFile: String) {
        LogUtils.printLog("scm info failed, upload $inputFile")
        CodeccWeb.upload(landunParam = commandParam.landunParam,
                filePath = inputFile,
                resultName = streamName + "_"  + commandParam.landunParam.buildId + "_scm_info_input.json",
                uploadType = "SCM_JSON",
                toolName = toolName)
    }

    override fun uploadInputFile(inputFile: String) {
        CodeccWeb.upload(landunParam = commandParam.landunParam,
                filePath = inputFile,
                resultName = streamName + "_"  + commandParam.landunParam.buildId + "_scm_info_input.json",
                uploadType = "SCM_JSON",
                toolName = toolName)
    }

    override fun scmOpSuccess(outputFile: String) {
        appendOutputFile(outputFile)
        LogUtils.printLog("scm info success")
        CodeccWeb.upload(landunParam = commandParam.landunParam,
                filePath = outputFile,
                resultName = streamName + "_" + commandParam.landunParam.buildId + "_scm_info.json",
                uploadType = "SCM_JSON",
                toolName = toolName)
        LogUtils.printLog("Upload scm info success")
    }

    override fun generateInputFile(): String {
        val inputFile = commandParam.dataRootPath+ File.separator + "scm_info_input.json"
        val dirPathList = mutableListOf<String>()
        if (commandParam.repos.filterNot { it.relPath.isBlank() }.isNotEmpty()) {
            dirPathList.addAll(commandParam.repos.map { CommonUtils.changePathToDocker(commandParam.landunParam.streamCodePath + File.separator + it.relPath) })
        } else {
            dirPathList.add(CommonUtils.changePathToDocker(commandParam.landunParam.streamCodePath))
        }
        val inputData = mapOf("dir_path_list" to dirPathList)
        val inputDataStr = jacksonObjectMapper().writeValueAsString(inputData)
        val file = File(inputFile)
        file.writeText(inputDataStr)

        LogUtils.printLog("finish write input data str: ${file.canonicalPath}, ${file.length()}")

        return inputFile
    }

    override fun generateLocalInputFile(): String {
        val inputFile = commandParam.dataRootPath+ File.separator + "scm_info_input.json"
        val dirPathList = mutableListOf<String>()
        if (commandParam.repos.filterNot { it.relPath.isBlank() }.isNotEmpty()) {
            dirPathList.addAll(commandParam.repos.map { CommonUtils.changePathToCurrentOS(commandParam.landunParam.streamCodePath + File.separator + it.relPath) })
        } else {
            dirPathList.add(CommonUtils.changePathToCurrentOS(commandParam.landunParam.streamCodePath))
        }
        val inputData = mapOf("dir_path_list" to dirPathList)
        val inputDataStr = jacksonObjectMapper().writeValueAsString(inputData)
        val file = File(inputFile)
        file.writeText(inputDataStr)

        LogUtils.printLog("finish write input data str: ${file.canonicalPath}, ${file.length()}")

        return inputFile
    }

    override fun generateOutputFile() = commandParam.dataRootPath + File.separator + "scm_info_output.json"
    override fun runCmd(imageParam: ImageParam, inputFile: String, outputFile: String) {

    }
}
