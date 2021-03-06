REM Assumes that $GUROBI_HOME$ and $GRB_LICENSE_FILE$ are set and that maven is installed and on the path
set maven_bin="mvn"
for /f "tokens=1,2 delims==" %%a in ('findstr /B VERSION %GRB_LICENSE_FILE%') do set GRB_VER_NAME=%%a&set GRB_VER=%%b

echo Found Gurobi Version: %GRB_VER%

if not "%GRB_VER%"=="6" (
	echo ERROR: Gurobi Version 6 is required
) else (
	call %maven_bin% install:install-file -Dfile=%GUROBI_HOME%/lib/gurobi.jar -DgroupId=gurobi -DartifactId=gurobi -Dversion=6.5 -Dpackaging=jar
	echo Added gurobi.jar to local maven repository.
	call %maven_bin% clean
)