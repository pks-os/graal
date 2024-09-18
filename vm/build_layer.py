import json
import os
import subprocess
import sys
from pathlib import Path

def generate_matrix(path_to_data):
    with open(f'{path_to_data}/popular-maven-libraries.json', 'r') as f:
        data = json.load(f)
    with open(f'{path_to_data}/exclude-file.json', 'r') as f:
        exclude_data = json.load(f)
    matrix = {'coordinates': []}
    for lib in data:
        skip = False
        for excluded_lib in exclude_data:
            if excluded_lib['group_id'] == lib['group_id'] and excluded_lib['artifact_id'] == lib['artifact_id']:
                skip = True
                break
        #if not skip:
        if True: # Faulty on purpose
            matrix['coordinates'].append(f'{lib['group_id']}:{lib['artifact_id']}:{lib['version']}')
        
    print(f"::set-output name=matrix::{json.dumps(matrix)}")

def build_layer(gav, native_image_path):
    currDir = os.getcwd()
    splitted = gav.split(':')
    group_id, artifact_id, version = splitted[0], splitted[1], splitted[2]
    
    subprocess.call(f'mvn dependency:get -Dartifact={gav} -Dtransitive=true', shell=True)

    home_path = str(Path.home())
    library_path = f'{home_path}/.m2/repository/{group_id.replace('.','/')}/{artifact_id}/{version}'
    jar_path = f'{library_path}/{artifact_id}-{version}.jar'
    subprocess.call(f'cp {library_path}/{artifact_id}-{version}.pom {library_path}/pom.xml', shell=True)
    if Path(library_path).exists():
        subprocess.call(f'mkdir {gav}', shell=True)
        os.chdir(gav)
        image_path = os.getcwd()
        os.chdir(library_path)
        dependency_path = os.popen('mvn -q exec:exec -Dexec.executable=echo -Dexec.args="%classpath"').read().rstrip()
        os.chdir(image_path)
        r = subprocess.call(f'{native_image_path} -H:+UnlockExperimentalVMOptions -cp {jar_path}:{dependency_path} -H:LayerCreate=layer.nil,package={jar_path} -H:+ReportExceptionStackTraces --no-fallback -o {artifact_id}-{version}', shell=True)
        os.chdir('..')
    os.chdir(currDir)
    sys.exit(r)